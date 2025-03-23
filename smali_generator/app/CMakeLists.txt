cmake_minimum_required(VERSION 3.22.1)

project("ultimate-patcher")

add_library(${CMAKE_PROJECT_NAME} SHARED src/main/cpp/hook.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME} android log)
