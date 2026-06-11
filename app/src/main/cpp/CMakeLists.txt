cmake_minimum_required(VERSION 3.22.1)

project("flacplayer")

add_library(
        flacplayer
        SHARED
        native-lib.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        flacplayer
        ${log-lib}
)
