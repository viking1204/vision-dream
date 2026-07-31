import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
TIMING_HEADER = ROOT / "app/src/main/cpp/src/UnetExecutionTiming.hpp"
QNN_MODEL = ROOT / "app/src/main/cpp/src/QnnModel.hpp"


class NativeUnetTimingContractTest(unittest.TestCase):
    def test_native_execution_timer_excludes_cpu_work_outside_the_qnn_call(self):
        """Compile and run the same native timer used around every UNet execute."""
        source = r'''
#include <chrono>
#include <cstdint>
#include <thread>
#include "UnetExecutionTiming.hpp"

int main() {
  int64_t unet_ms = 0;
  std::this_thread::sleep_for(std::chrono::milliseconds(25));  // CPU preparation
  measureUnetExecutionMillis(unet_ms, [] {
    std::this_thread::sleep_for(std::chrono::milliseconds(20));  // QNN execute
  });
  std::this_thread::sleep_for(std::chrono::milliseconds(25));  // CFG/output work
  return unet_ms >= 15 && unet_ms < 40 ? 0 : 1;
}
'''
        with tempfile.TemporaryDirectory() as directory:
            source_path = Path(directory) / "unet_timing_test.cpp"
            executable = Path(directory) / "unet_timing_test"
            source_path.write_text(source)
            subprocess.run(
                ["c++", "-std=c++17", "-I", str(TIMING_HEADER.parent), str(source_path), "-o", str(executable)],
                check=True,
                capture_output=True,
                text=True,
            )
            subprocess.run([str(executable)], check=True, capture_output=True, text=True)

    def test_qnn_unet_timer_is_inside_graph_execute_not_its_cpu_tensor_marshalling(self):
        """Protect the QNN path from moving timing back around execute* wrappers."""
        source = QNN_MODEL.read_text()
        for method in ("executeUnetGraphs", "executeUnetGraphsSDXL"):
            start = source.index(f"StatusCode {method}")
            timer = source.index("auto start_time", start)
            graph_execute = source.index("graphExecute", timer)
            accumulator = source.index("unet_execution_ms += duration", graph_execute)
            measured = source[timer:accumulator]
            self.assertNotIn("memcpy", measured)
            self.assertNotIn("floatToTfN", measured)
            self.assertNotIn("convertToFloat", measured)
            self.assertLess(graph_execute, accumulator)

        anima_start = source.index("bool runGraph")
        anima_graph_execute = source.index("graphExecute", anima_start)
        anima_accumulator = source.index("*unet_execution_ms += duration_ms", anima_graph_execute)
        self.assertLess(anima_graph_execute, anima_accumulator)
        self.assertIn('runGraph(graphInfo, "anima unet part1", &unet_execution_ms)', source)
        self.assertIn('runGraph(graphInfo, "anima unet part2", &unet_execution_ms)', source)
