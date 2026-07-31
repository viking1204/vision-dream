#ifndef UNET_EXECUTION_TIMING_HPP
#define UNET_EXECUTION_TIMING_HPP

#include <chrono>
#include <cstdint>
#include <type_traits>
#include <utility>

// Measures only the supplied native inference invocation.  Callers must keep
// tensor copies, CFG composition, tiling and output conversion outside this
// wrapper so `unet_ms` remains comparable across execution paths.
template <typename Operation>
decltype(auto) measureUnetExecutionMillis(int64_t &total_ms,
                                          Operation &&operation) {
  const auto started = std::chrono::high_resolution_clock::now();
  try {
    if constexpr (std::is_void_v<std::invoke_result_t<Operation>>) {
      std::forward<Operation>(operation)();
      total_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::high_resolution_clock::now() - started)
                      .count();
    } else {
      auto result = std::forward<Operation>(operation)();
      total_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                      std::chrono::high_resolution_clock::now() - started)
                      .count();
      return result;
    }
  } catch (...) {
    total_ms += std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::high_resolution_clock::now() - started)
                    .count();
    throw;
  }
}

#endif  // UNET_EXECUTION_TIMING_HPP
