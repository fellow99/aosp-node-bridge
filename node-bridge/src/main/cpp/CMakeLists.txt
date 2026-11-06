# CMakeLists.txt —— 备选构建方式（本机默认走 tools/build-bridge.mjs，不需要 CMake/ninja）
#
# 用 NDK toolchain file 构建：
#   cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
#         -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
#   cmake --build build
#
# 注意：两个 .so 的链接语义不同（见 node_host_client.c 文件头）：
#   · libnode_bridge.so   只链 log/dl，不链 libnode（靠 dlopen）
#   · libnode_host_client.so 必须留下 DT_NEEDED libnode.so（但 napi_* 运行期解析）

cmake_minimum_required(VERSION 3.18)
project(node_bridge LANGUAGES C)

set(CMAKE_C_STANDARD 11)

# ── 引导件 ────────────────────────────────────────────────────────────────────
add_library(node_bridge SHARED node_bridge.c)
target_compile_options(node_bridge PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_options(node_bridge PRIVATE -Wl,-soname,libnode_bridge.so)
target_link_libraries(node_bridge PRIVATE log dl android)

# ── NAPI addon（需要 Node 头文件；编译期只需头文件，链接期导入 libnode.so）────────
# 头文件随 bridge 仓库自带（NAPI 是稳定 ABI，node_api.h 跨 Node 大版本极少变动）。
# addon 必须显式链接 libnode.so 留下 DT_NEEDED（见文件头注释）：运行期 linker 按
# 依赖图解析 napi_*（API 26 linker 不会把已 dlopen 库的符号供给无依赖关系的 addon）。
# libnode.so 位置可经 NODE_LIBBIN 指定（如 -DNODE_LIBBIN=<node-runtime>/jni/arm64-v8a）；
# 未指定时退化为不显式链接（同 tools/build-bridge.mjs --libnode 缺失的跳过语义由调用方控制）。
set(NODE_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include/node" CACHE PATH "Node NAPI headers")
set(NODE_LIBBIN "" CACHE PATH "Directory containing libnode.so to link against (leave empty to skip explicit link)")

if(EXISTS "${NODE_INCLUDE_DIR}/node_api.h")
  add_library(node_host_client SHARED node_host_client.c)
  target_include_directories(node_host_client PRIVATE "${NODE_INCLUDE_DIR}")
  target_compile_options(node_host_client PRIVATE -Wall -Wextra -fvisibility=hidden)
  target_link_options(node_host_client PRIVATE
    -Wl,-soname,libnode_host_client.so
    -Wl,-z,undefs
    -Wl,--no-as-needed)
  if(EXISTS "${NODE_LIBBIN}/libnode.so")
    target_link_libraries(node_host_client PRIVATE log "${NODE_LIBBIN}/libnode.so")
  else()
    target_link_libraries(node_host_client PRIVATE log)
    message(WARNING "NODE_LIBBIN 未指向含 libnode.so 的目录：addon 将不带 DT_NEEDED libnode.so（API<30 linker 符号解析缺陷，见 build-bridge.mjs 注释）")
  endif()
else()
  message(WARNING "跳过 node_host_client：缺 ${NODE_INCLUDE_DIR}/node_api.h")
endif()

