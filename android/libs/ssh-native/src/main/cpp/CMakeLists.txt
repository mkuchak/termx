cmake_minimum_required(VERSION 3.22.1)
project(termxpty C)

add_library(termxpty SHARED pty_fork_exec.c)

# Android's logging lib for __android_log_print.
find_library(log-lib log)
target_link_libraries(termxpty ${log-lib})

# -fvisibility=hidden would save a few bytes, but the JNI_OnLoad path
# is easier to debug if JNIEXPORT symbols stay visible by default.
target_compile_options(termxpty PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-unused-parameter
)
