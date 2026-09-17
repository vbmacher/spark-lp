package org.apache.spark.wrappers

import com.github.fommil.netlib.{BLAS => NetlibBLAS, LAPACK => NetlibLAPACK}

/** Loads the bundled Linux AArch64 LAPACK wrapper before netlib-java chooses a backend. */
object NativeNetlib {
  private val Resource = "netlib-native_system-linux-aarch64.so"
  private val LapackProperty = "com.github.fommil.netlib.NativeSystemLAPACK.natives"

  private def configureLapack(): Unit = {
    val architecture = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT)
    val linux = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux")
    val bundled = getClass.getClassLoader.getResource(Resource) != null
    if (linux && Set("aarch64", "arm64").contains(architecture) && bundled) {
      setDefault(LapackProperty)
    }
  }

  lazy val blas: NetlibBLAS = NetlibBLAS.getInstance()

  lazy val lapack: NetlibLAPACK = {
    configureLapack()
    NetlibLAPACK.getInstance()
  }

  private def setDefault(property: String): Unit = {
    if (System.getProperty(property) == null) System.setProperty(property, Resource)
  }
}
