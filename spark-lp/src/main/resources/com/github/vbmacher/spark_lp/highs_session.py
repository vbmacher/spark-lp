"""Optional HiGHS JSON-lines bridge. No model editing or arbitrary code execution protocol."""
import json
import math
import os
import sys

# Keep the protocol on its own descriptor. Native C/C++ stdout belongs in the bounded log.
protocol = os.fdopen(os.dup(sys.stdout.fileno()), "w", buffering=1)
os.dup2(sys.stderr.fileno(), sys.stdout.fileno())

try:
    import highspy as hs
    h = hs.Highs()
    cb = hs.cb
    provider = "highspy"
except ImportError:
    from scipy.optimize._highspy import _core as hs
    h = hs._Highs()
    cb = hs.cb
    provider = "scipy.optimize._highspy"


def emit(value):
    print(json.dumps(value, allow_nan=False), file=protocol, flush=True)


def finite(value):
    return value if math.isfinite(value) else None


def checked(status):
    if status == hs.HighsStatus.kError:
        raise ValueError("HiGHS rejected the operation")


def callback(kind, message, output, input_data, user_data):
    emit({"event": str(kind), "message": message,
          "fields": {"objective": str(output.objective_function_value)}})


h.setOptionValue("log_to_console", False)
h.setOptionValue("output_flag", False)
checked(h.readModel(sys.argv[1]))
emit({"ok": True, "provider": provider, "version": h.version()})
for line in sys.stdin:
    try:
        request = json.loads(line)
        op = request["op"]
        if op == "setParameter":
            name = request["name"]
            # Console output would corrupt the protocol; model/solution paths remain explicit operations.
            if name in ("log_to_console", "log_file", "solution_file", "write_solution_to_file", "read_solution_file"):
                raise ValueError("Parameter is managed by the session")
            status, current = h.getOptionValue(name)
            checked(status)
            text = request["value"]
            if isinstance(current, bool):
                if text.lower() not in ("true", "false"):
                    raise ValueError("Boolean parameter requires true or false")
                value = text.lower() == "true"
            elif isinstance(current, int):
                value = int(text)
            elif isinstance(current, float):
                value = float(text)
                if not math.isfinite(value):
                    raise ValueError("Numeric parameter must be finite")
            else:
                value = text
            checked(h.setOptionValue(name, value))
            emit({"ok": True})
        elif op in ("parameter", "information"):
            status, value = (h.getOptionValue(request["name"]) if op == "parameter"
                             else h.getInfoValue(request["name"]))
            checked(status)
            emit({"ok": True, "value": str(value)})
        elif op == "callback":
            checked(h.setCallback(callback, None))
            checked(h.startCallback(cb.kCallbackLogging))
            checked(h.startCallback(cb.kCallbackMipImprovingSolution))
            checked(h.setOptionValue("log_to_console", True))
            checked(h.setOptionValue("output_flag", True))
            emit({"ok": True})
        elif op == "readSolution":
            checked(h.readSolution(request["path"], 0))
            emit({"ok": True})
        elif op == "writeSolution":
            checked(h.writeSolution(request["path"], 0))
            emit({"ok": True})
        elif op == "solve":
            # The bridge wall-clock deadline remains enforced by the JVM as well.
            if "seconds" in request:
                checked(h.setOptionValue("time_limit", float(request["seconds"])))
            checked(h.run())
            lp, solution, info = h.getLp(), h.getSolution(), h.getInfo()
            mip = bool(lp.integrality_) and any(int(v) != 0 for v in lp.integrality_)
            emit({"ok": True, "status": str(h.getModelStatus()),
                  "values": dict(zip(lp.col_names_, list(solution.col_value))) if solution.value_valid else None,
                  "duals": dict(zip(lp.row_names_, list(solution.row_dual))) if solution.dual_valid and not mip else None,
                  "costs": dict(zip(lp.col_names_, list(solution.col_dual))) if solution.dual_valid and not mip else None,
                  "objective": finite(info.objective_function_value) if solution.value_valid else None,
                  "bestBound": finite(info.mip_dual_bound) if mip else None,
                  "iterations": max(0, info.simplex_iteration_count) + max(0, info.ipm_iteration_count),
                  "nodes": str(info.mip_node_count)})
        elif op == "close":
            h.clear()
            emit({"ok": True})
            break
        else:
            raise ValueError("Unsupported native operation")
    except Exception as error:
        emit({"ok": False, "error": str(error)})
