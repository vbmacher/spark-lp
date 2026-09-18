package com.github.vbmacher.spark_lp

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import com.github.vbmacher.spark_lp.support.EmrTestbed
import org.json4s._
import org.json4s.jackson.JsonMethods.parse
import org.scalatest.funsuite.AnyFunSuite

class EmrTestbedSuite extends AnyFunSuite {
  private val runtime = Map("java" -> "11.0.26", "architecture" -> "aarch64", "lapack" -> "native")
  private val cluster = parse("""{"Cluster":{
    "Id":"j-FIRST","Name":"temporary cluster","ReleaseLabel":"emr-7.3.0",
    "RunningAmiVersion":"2023.5","Status":{"State":"WAITING"},"InstanceCollectionType":"INSTANCE_GROUP",
    "InstanceGroups":[
      {"Id":"ig-primary","InstanceGroupType":"MASTER","RequestedInstanceCount":1},
      {"Id":"ig-workers","InstanceGroupType":"CORE","RequestedInstanceCount":2}],
    "Applications":[{"Name":"Spark","Version":"3.5.1-amzn-1"},{"Name":"Hadoop","Version":"3.3.6"}]
  }}""")
  private def instance(id: String, group: String, kind: String): JValue = parse(s"""{
    "Id":"$id","InstanceGroupId":"$group","InstanceType":"$kind","Market":"ON_DEMAND",
    "PrivateIpAddress":"10.0.0.1","Status":{"State":"RUNNING"}}
  """)
  private val primary = instance("i-1", "ig-primary", "m7g.xlarge")
  private val worker = instance("i-2", "ig-workers", "r7g.4xlarge")
  private val instances = JObject("Instances" -> JArray(List(primary, worker, instance("i-3", "ig-workers", "r7g.4xlarge"))))
  private def name(c: JValue = cluster, i: JValue = instances, r: Map[String, String] = runtime): String = EmrTestbed.name(c, i, r)
  private def field(value: JValue, key: String, replacement: JValue): JValue = value.transformField {
    case (`key`, _) => key -> replacement
  }

  test("automatic name is readable, deterministic, slug-safe and within Bencher's 64-character limit") {
    val result = name()
    assert(result.matches("emr-7-3-0-r7g-4xlarge-2w-[0-9a-f]{7}"))
    assert(result.matches("[a-z0-9-]+") && result.length <= 64)
    assert(result == name())
    val reordered = cluster.transform { case JArray(xs) => JArray(xs.reverse); case JObject(xs) => JObject(xs.reverse) }
    assert(result == name(reordered, instances.transform { case JArray(xs) => JArray(xs.reverse) }, runtime.toSeq.reverse.toMap))
  }

  test("cluster names, IDs, node IDs, group IDs and addresses do not split equivalent testbeds") {
    // Group IDs only join metadata and do not enter the fingerprint.
    val renamedCluster = cluster.transformField {
      case ("Id", JString(id)) => "Id" -> JString("new-" + id)
      case ("Name", JString("temporary cluster")) => "Name" -> JString("new name")
    }
    val renamedInstances = instances.transformField {
      case ("InstanceGroupId", JString(id)) => "InstanceGroupId" -> JString("new-" + id)
      case ("Id", _) => "Id" -> JString("new-instance")
      case ("PrivateIpAddress", _) => "PrivateIpAddress" -> JString("10.1.1.1")
    }
    assert(name() == name(renamedCluster, renamedInstances))
  }

  test("release, AMI, application versions, primary/worker types, markets and runtime changes split identities") {
    Seq(field(cluster, "ReleaseLabel", JString("emr-7.4.0")),
      field(cluster, "RunningAmiVersion", JString("2023.6")),
      field(cluster, "Version", JString("different"))).foreach(c => assert(name(c) != name()))
    assert(name(i = field(instances, "InstanceType", JString("m7i.4xlarge"))) != name())
    assert(name(i = field(instances, "Market", JString("SPOT"))) != name())
    assert(name(r = runtime.updated("lapack", "java")) != name())
    val changedPrimary = JObject("Instances" -> JArray(List(field(primary, "InstanceType", JString("m7g.2xlarge")), worker, worker)))
    assert(name(i = changedPrimary) != name())
  }

  test("node counts and roles distinguish otherwise identical hardware") {
    val larger = cluster.transformField { case ("RequestedInstanceCount", JInt(n)) if n == 2 => "RequestedInstanceCount" -> JInt(3) }
    assert(name(larger, JObject("Instances" -> JArray(List(primary, worker, worker, worker)))) != name())
    val tasks = cluster.transformField { case ("InstanceGroupType", JString("CORE")) => "InstanceGroupType" -> JString("TASK") }
    assert(name(tasks) != name())
  }

  test("mixed instance fleets use actual types, not their requested candidate types") {
    val fleetCluster = parse("""{"Cluster":{"ReleaseLabel":"emr-7.3.0","RunningAmiVersion":"2023.5",
      "Status":{"State":"WAITING"},"InstanceCollectionType":"INSTANCE_FLEET",
      "InstanceFleets":[
        {"Id":"if-primary","InstanceFleetType":"MASTER","TargetOnDemandCapacity":1,"ProvisionedOnDemandCapacity":1,"TargetSpotCapacity":0,"ProvisionedSpotCapacity":0},
        {"Id":"if-workers","InstanceFleetType":"CORE","TargetOnDemandCapacity":2,"ProvisionedOnDemandCapacity":2,"TargetSpotCapacity":0,"ProvisionedSpotCapacity":0}],
      "Applications":[{"Name":"Spark","Version":"3.5.1-amzn-1"},{"Name":"Hadoop","Version":"3.3.6"}]}}""")
    val fleetInstances = instances.transformField {
      case ("InstanceGroupId", JString(id)) => "InstanceFleetId" -> JString(id.replace("ig-", "if-"))
    }
    assert(name(fleetCluster, fleetInstances) == name())
    intercept[IllegalArgumentException](name(field(fleetCluster, "ProvisionedOnDemandCapacity", JInt(0)), fleetInstances))
  }

  test("incomplete and changing cluster inventories fail instead of using a workstation testbed") {
    Seq(JObject(), field(cluster, "ReleaseLabel", JNull), field(cluster, "Applications", JArray(Nil)),
      field(cluster, "Status", JObject("State" -> JString("STARTING"))),
      field(cluster, "RequestedInstanceCount", JInt(99))).foreach(c => intercept[IllegalArgumentException](name(c)))
    Seq(field(instances, "Status", JObject("State" -> JString("BOOTSTRAPPING"))),
      field(instances, "InstanceGroupId", JString("missing")), JObject("Instances" -> JArray(Nil)))
      .foreach(i => intercept[IllegalArgumentException](name(i = i)))
  }

  test("discovery reads cluster ID locally and issues only the two read-only AWS commands") {
    val path = Files.createTempFile("emr-job-flow-", ".json")
    try {
      Files.write(path, "{\"jobFlowId\":\"j-FIRST\"}".getBytes(UTF_8))
      var commands = Vector.empty[Vector[String]]
      def query(args: Vector[String]): JValue = {
        commands :+= args
        if (args.contains("describe-cluster")) cluster else instances
      }
      assert(EmrTestbed.discover(path, Map("AWS_REGION" -> "", "AWS_DEFAULT_REGION" -> "eu-west-1"), query,
        () => fail("Should not contact IMDS when region is configured"), runtime) == name())
      assert(commands.size == 2 && commands.forall(_.take(3) == Vector("--region", "eu-west-1", "emr")))
      assert(commands.map(_(3)) == Vector("describe-cluster", "list-instances"))
      assert(commands.last.takeRight(5) == Vector("--instance-states", "AWAITING_FULFILLMENT", "PROVISIONING", "BOOTSTRAPPING", "RUNNING"))
      commands = Vector.empty
      assert(EmrTestbed.discover(path, Map.empty, query, () => "us-east-1", runtime) == name())
      assert(commands.forall(_(1) == "us-east-1"))
      intercept[IllegalStateException] {
        EmrTestbed.discover(path, Map("AWS_REGION" -> "eu-west-1"), _ => throw new IllegalStateException("AccessDenied"),
          () => "unused", runtime)
      }
      Files.write(path, "{\"jobFlowId\":\"invalid\"}".getBytes(UTF_8))
      intercept[IllegalArgumentException](EmrTestbed.discover(path, Map.empty, query, () => "us-east-1", runtime))
    } finally Files.deleteIfExists(path)
  }
}
