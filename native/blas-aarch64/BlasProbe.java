import com.github.fommil.netlib.BLAS;
import com.github.fommil.jni.JniLoader;
import java.util.Arrays;

public final class BlasProbe {
  public static void main(String[] args) {
    JniLoader.load("netlib-native_blas-linux-aarch64.so");
    BLAS blas = BLAS.getInstance();
    if (!blas.getClass().getName().endsWith("NativeSystemBLAS")) {
      throw new IllegalStateException("Expected NativeSystemBLAS, got " + blas.getClass().getName());
    }

    double[] x = {1.0, 2.0, 3.0};
    double[] y = {4.0, 5.0, 6.0};
    double dot = blas.ddot(3, x, 1, y, 1);
    blas.daxpy(3, 2.0, x, 1, y, 1);
    if (dot != 32.0 || !Arrays.equals(y, new double[] {6.0, 9.0, 12.0})) {
      throw new IllegalStateException("BLAS correctness check failed");
    }

    System.out.println("blas=" + blas.getClass().getName() + " dot=" + dot);
  }
}
