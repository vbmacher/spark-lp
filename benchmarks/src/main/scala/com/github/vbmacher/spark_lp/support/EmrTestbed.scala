package com.github.vbmacher.spark_lp.support

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.json4s._
import org.json4s.jackson.JsonMethods.{compact, parse, render}
import scala.collection.JavaConverters._

/** Stable environment identity, independent of cluster IDs, hostnames and benchmark revisions. */
object EmrTestbed {
  val JobFlow: Path = Paths.get("/mnt/var/lib/info/job-flow.json")

  private def string(value: JValue, field: String): String = (value \ field) match {
    case JString(s) if s.nonEmpty => s
    case _ => throw new IllegalArgumentException(s"Incomplete EMR metadata: $field")
  }
  private def array(value: JValue): List[JValue] = value match {
    case JArray(xs) => xs
    case _ => throw new IllegalArgumentException("Incomplete EMR metadata: expected an array")
  }
  private def canonical(value: JValue): JValue = value match {
    case JObject(fields) => JObject(fields.sortBy(_._1).map { case (key, v) => key -> canonical(v) })
    case JArray(xs) => JArray(xs.map(canonical).sortBy(v => compact(render(v))))
    case JNothing => JNull
    case other => other
  }

  def name(description: JValue, inventory: JValue, runtime: Map[String, String]): String = {
    val cluster = description \ "Cluster"
    val release = string(cluster, "ReleaseLabel")
    require(release.matches("emr-[a-zA-Z0-9.-]+"), "Invalid EMR release label")
    require(Set("WAITING", "RUNNING")(string(cluster \ "Status", "State")), "EMR cluster is not ready")
    val (collections, roleKey, membershipKey) = string(cluster, "InstanceCollectionType") match {
      case "INSTANCE_GROUP" => (array(cluster \ "InstanceGroups"), "InstanceGroupType", "InstanceGroupId")
      case "INSTANCE_FLEET" => (array(cluster \ "InstanceFleets"), "InstanceFleetType", "InstanceFleetId")
      case _ => throw new IllegalArgumentException("Unknown EMR instance collection type")
    }
    val roles = collections.map(g => string(g, "Id") -> string(g, roleKey)).toMap
    require(roles.size == collections.size && roles.values.forall(Set("MASTER", "CORE", "TASK")), "Invalid EMR node roles")
    val instances = array(inventory \ "Instances")
    require(instances.nonEmpty && instances.forall(i => string(i \ "Status", "State") == "RUNNING"),
      "EMR nodes must all be running; wait for provisioning/resizing to finish")
    collections.foreach { group =>
      val observed = instances.count(i => string(i, membershipKey) == string(group, "Id"))
      if (membershipKey == "InstanceGroupId") {
        require((group \ "RequestedInstanceCount") == JInt(observed), "EMR instance group is still resizing")
      } else {
        Seq("OnDemand", "Spot").foreach { market =>
          val target = group \ s"Target${market}Capacity"
          require(target.isInstanceOf[JInt] && target == (group \ s"Provisioned${market}Capacity"),
            "EMR instance fleet is still resizing")
        }
      }
    }
    val nodes = instances.map { instance =>
      val role = roles.getOrElse(string(instance, membershipKey), throw new IllegalArgumentException("Unknown EMR node group"))
      val kind = string(instance, "InstanceType")
      val market = string(instance, "Market")
      require(kind.matches("[a-zA-Z0-9.-]+") && Set("ON_DEMAND", "SPOT")(market), "Invalid EC2 instance metadata")
      (role, kind, market)
    }.groupBy(identity).map { case ((role, kind, market), members) =>
      JObject("role" -> JString(role), "type" -> JString(kind), "market" -> JString(market), "count" -> JInt(members.size))
    }.toList
    require(nodes.exists(n => (n \ "role") == JString("MASTER")) && nodes.exists(n => (n \ "role") != JString("MASTER")),
      "EMR benchmark requires primary and worker nodes")
    val applications = array(cluster \ "Applications").map { app =>
      JObject("name" -> JString(string(app, "Name").toLowerCase(Locale.ROOT)), "version" -> JString(string(app, "Version")))
    }
    require(applications.exists(a => (a \ "name") == JString("spark")), "EMR Spark version is missing")
    require(runtime.nonEmpty && runtime.values.forall(_.nonEmpty), "Runtime identity is missing")
    val descriptor = canonical(JObject("schema" -> JInt(1), "release" -> JString(release),
      "ami" -> (cluster \ "RunningAmiVersion"), "custom_ami" -> (cluster \ "CustomAmiId"),
      "applications" -> JArray(applications), "nodes" -> JArray(nodes),
      "runtime" -> JObject(runtime.toList.map { case (k, v) => k -> JString(v) })))
    val hash = MessageDigest.getInstance("SHA-256").digest(compact(render(descriptor)).getBytes(UTF_8))
      .take(4).map(b => f"${b & 0xff}%02x").mkString.take(7)
    val workers = nodes.filter(n => (n \ "role") != JString("MASTER"))
    val types = workers.map(n => string(n, "type")).distinct.sorted.mkString("-")
    val count = instances.count(i => roles(string(i, membershipKey)) != "MASTER")
    val prefix = s"$release-$types-${count}w".toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").take(47).stripSuffix("-")
    s"$prefix-$hash"
  }

  /** The only AWS operations here are read-only; bounded CLI calls need no extra JVM dependency. */
  private def aws(args: Vector[String]): JValue = {
    val output = Files.createTempFile("benchmark-emr-", ".json")
    try {
      val builder = new ProcessBuilder((Vector("aws") ++ args ++ Vector("--output", "json",
        "--cli-connect-timeout", "5", "--cli-read-timeout", "15")).asJava)
        .redirectOutput(output.toFile).redirectError(ProcessBuilder.Redirect.INHERIT)
      builder.environment().put("AWS_PAGER", "")
      builder.environment().put("AWS_MAX_ATTEMPTS", "1")
      val process = builder.start()
      try {
        require(process.waitFor(60, TimeUnit.SECONDS), "EMR metadata lookup timed out")
        require(process.exitValue() == 0, "EMR metadata lookup failed; check AWS CLI credentials and read permissions")
        parse(new String(Files.readAllBytes(output), UTF_8))
      } finally if (process.isAlive) process.destroyForcibly().waitFor()
    } finally Files.deleteIfExists(output)
  }

  private def instanceRegion(): String = {
    def request(path: String, method: String, header: (String, String)): String = {
      val connection = new URL(s"http://169.254.169.254/latest/$path").openConnection().asInstanceOf[HttpURLConnection]
      connection.setConnectTimeout(2000); connection.setReadTimeout(2000)
      connection.setRequestMethod(method); connection.setRequestProperty(header._1, header._2)
      try {
        val source = scala.io.Source.fromInputStream(connection.getInputStream, "UTF-8")
        try source.mkString finally source.close()
      } finally connection.disconnect()
    }
    val token = request("api/token", "PUT", "X-aws-ec2-metadata-token-ttl-seconds" -> "60")
    string(parse(request("dynamic/instance-identity/document", "GET", "X-aws-ec2-metadata-token" -> token)), "region")
  }

  def discover(jobFlow: Path = JobFlow, environment: Map[String, String] = sys.env,
               query: Vector[String] => JValue = aws, regionLookup: () => String = () => instanceRegion(),
               runtime: Map[String, String] = runtimeIdentity): String = {
    val clusterId = string(parse(new String(Files.readAllBytes(jobFlow), UTF_8)), "jobFlowId")
    require(clusterId.matches("j-[a-zA-Z0-9]+"), "Invalid EMR cluster ID")
    val region = environment.get("AWS_REGION").filter(_.nonEmpty)
      .orElse(environment.get("AWS_DEFAULT_REGION").filter(_.nonEmpty)).getOrElse(regionLookup())
    require(region.matches("[a-z0-9-]+"), "Invalid AWS region")
    val common = Vector("--region", region, "emr")
    val cluster = query(common ++ Vector("describe-cluster", "--cluster-id", clusterId))
    val instances = query(common ++ Vector("list-instances", "--cluster-id", clusterId, "--instance-states",
      "AWAITING_FULFILLMENT", "PROVISIONING", "BOOTSTRAPPING", "RUNNING"))
    name(cluster, instances, runtime)
  }

  private[support] def runtimeIdentity: Map[String, String] = Map(
    "java" -> System.getProperty("java.runtime.version"), "jvm" -> System.getProperty("java.vm.name"),
    "os" -> System.getProperty("os.name"), "kernel" -> System.getProperty("os.version"),
    "architecture" -> System.getProperty("os.arch"),
    "blas" -> org.apache.spark.wrappers.NativeNetlib.blas.getClass.getName,
    "lapack" -> org.apache.spark.wrappers.NativeNetlib.lapack.getClass.getName)
}
