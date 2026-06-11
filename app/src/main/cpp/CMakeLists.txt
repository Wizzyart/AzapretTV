cmake_minimum_required(VERSION 3.22.1)

project(azapret_native)

add_library(azapret_native SHARED native_process.c)

target_link_libraries(azapret_native log)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(azapret_byedpi SHARED ${BYE_DPI_SRC} azapret_byedpi.c)
target_include_directories(azapret_byedpi PRIVATE byedpi)
target_compile_options(azapret_byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(azapret_byedpi PRIVATE ANDROID_APP)
target_link_libraries(azapret_byedpi PRIVATE android log)
