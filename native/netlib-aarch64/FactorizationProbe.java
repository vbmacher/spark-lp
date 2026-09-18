import com.github.fommil.netlib.LAPACK;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import org.netlib.util.intW;

public final class FactorizationProbe {
  private static double offDiagonal(int row, int column) {
    int mixed = Math.floorMod(row * 131 + column * 17, 101) - 50;
    return mixed * 1.0e-7;
  }

  private static double[] packedMatrix(int n) {
    double[] matrix = new double[n * (n + 1) / 2];
    int offset = 0;
    for (int column = 0; column < n; column++) {
      for (int row = 0; row < column; row++) {
        matrix[offset + row] = offDiagonal(row, column);
      }
      matrix[offset + column] = 1.0;
      offset += column + 1;
    }
    return matrix;
  }

  private static double[] fullMatrix(double[] packed, int n) {
    double[] full = new double[n * n];
    int offset = 0;
    for (int column = 0; column < n; column++) {
      System.arraycopy(packed, offset, full, column * n, column + 1);
      offset += column + 1;
    }
    return full;
  }

  private static double[] rhs(int n) {
    double[] result = new double[n];
    for (int row = 0; row < n; row++) {
      double value = 1.0;
      for (int column = 0; column < n; column++) {
        if (row != column) {
          value += offDiagonal(Math.min(row, column), Math.max(row, column));
        }
      }
      result[row] = value;
    }
    return result;
  }

  private static long cpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean)
      ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
  }

  public static void main(String[] args) {
    Locale.setDefault(Locale.ROOT);
    String variant = args[0];
    int n = Integer.parseInt(args[1]);
    int repetitions = Integer.parseInt(args[2]);
    int warmups = Integer.parseInt(args[3]);
    LAPACK lapack = LAPACK.getInstance();
    double[] source = packedMatrix(n);
    double[] originalRhs = rhs(n);

    for (int repetition = -warmups; repetition < repetitions; repetition++) {
      double[] b = originalRhs.clone();
      intW info = new intW(0);
      long conversionStart = System.nanoTime();
      double[] factor = variant.equals("full") ? fullMatrix(source, n) : source.clone();
      double conversionSeconds = (System.nanoTime() - conversionStart) / 1.0e9;
      long cpuStart = cpuNanos();
      long factorStart = System.nanoTime();
      if (variant.equals("full")) {
        lapack.dpotrf("U", n, factor, n, info);
      } else {
        lapack.dpptrf("U", n, factor, info);
      }
      double factorSeconds = (System.nanoTime() - factorStart) / 1.0e9;
      long solveStart = System.nanoTime();
      if (variant.equals("full")) {
        lapack.dpotrs("U", n, 1, factor, n, b, n, info);
      } else {
        lapack.dpptrs("U", n, 1, factor, b, n, info);
      }
      double solveSeconds = (System.nanoTime() - solveStart) / 1.0e9;
      double cpuSeconds = (cpuNanos() - cpuStart) / 1.0e9;
      double error = 0.0;
      for (double value : b) error = Math.max(error, Math.abs(value - 1.0));
      System.out.printf(
        "{\"variant\":\"%s\",\"n\":%d,\"repetition\":%d,\"warmup\":%s," +
          "\"lapack\":\"%s\",\"conversion_seconds\":%.9f,\"factor_seconds\":%.9f," +
          "\"solve_seconds\":%.9f,\"cpu_seconds\":%.9f," +
          "\"max_solution_error\":%.17g,\"info\":%d}%n",
        variant, n, Math.max(repetition, 0), repetition < 0, lapack.getClass().getName(),
        conversionSeconds, factorSeconds, solveSeconds, cpuSeconds, error, info.val);
    }
  }
}
