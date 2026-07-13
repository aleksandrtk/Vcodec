cmake_minimum_required(VERSION 3.22.1)

project("metadata-restorer")

add_library(
    metadata-restorer
    SHARED
    metadata-restorer.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    metadata-restorer
    ${log-lib}
)
