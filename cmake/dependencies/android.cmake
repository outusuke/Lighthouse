# Third-party dependencies for the Android build.
#
# Every other platform gets these from a package manager — vcpkg on Windows, the
# distro on Linux, Homebrew on macOS, devkitPro on Switch. The NDK build has no
# such source, so what libultraship's own cmake/dependencies/android.cmake does
# not already fetch (SDL2, nlohmann_json, tinyxml2, spdlog, libzip) is fetched
# here.
#
# This has to be included before add_subdirectory(libultraship):
# OVERRIDE_FIND_PACKAGE is what makes the later find_package(<name> REQUIRED)
# calls resolve to these instead of failing.

include(FetchContent)

# =================================== SDL2 ===================================
# Declared here rather than left to libultraship's android.cmake purely so the
# version is visible next to the note below. FetchContent honours the first
# declaration of a name, so declaring it before add_subdirectory(libultraship)
# is what pins it.
#
# Keep in step with SDL_MICRO_VERSION in
# android/app/src/main/java/org/libsdl/app/SDLActivity.java: SDLActivity refuses
# to start when the Java glue and libSDL2.so disagree on their version.
find_package(SDL2 QUIET)
if(NOT SDL2_FOUND)
    FetchContent_Declare(
        SDL2
        GIT_REPOSITORY https://github.com/libsdl-org/SDL.git
        GIT_TAG release-2.32.10
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(SDL2)
endif()

# ================================= SDL2_net =================================
# What USE_NETWORKING (the Anchor online features) links against. A thin
# wrapper over BSD sockets, which Bionic provides.
#
# Built shared, and that is not a free choice: SDL_net picks which SDL2 target
# to look for from BUILD_SHARED_LIBS, asking for SDL2::SDL2-static in a static
# build. This app builds SDL2 shared (SDLActivity dlopens libSDL2.so), so the
# static target does not exist and SDL_net falls back to hunting for an
# installed SDL2 that the NDK has none of. Shared makes it ask for SDL2::SDL2,
# which is the target already in the build. The extra libSDL2_net.so is picked
# up by the Android plugin along with the others, and the linker resolves it as
# a dependency of libmain.so without the Java side naming it.
find_package(SDL2_net QUIET)
if(USE_NETWORKING AND NOT SDL2_net_FOUND)
    set(SDL2NET_SAMPLES OFF)
    set(SDL2NET_INSTALL OFF)
    set(_lh_saved_build_shared_libs ${BUILD_SHARED_LIBS})
    set(BUILD_SHARED_LIBS ON)
    FetchContent_Declare(
        SDL2_net
        GIT_REPOSITORY https://github.com/libsdl-org/SDL_net.git
        GIT_TAG release-2.2.0
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(SDL2_net)
    set(BUILD_SHARED_LIBS ${_lh_saved_build_shared_libs})

    # The engine includes <SDL2/SDL_net.h> — the path a system install gives it.
    # The fetched source keeps SDL_net.h at its root and installs the SDL2/
    # prefix only on `make install`, which never runs here, so mirror the header
    # into the build tree the same way SDL2's own build stages its headers.
    # Attached to the target so anything linking SDL2_net::SDL2_net picks it up.
    file(MAKE_DIRECTORY "${sdl2_net_BINARY_DIR}/include/SDL2")
    file(COPY "${sdl2_net_SOURCE_DIR}/SDL_net.h" DESTINATION "${sdl2_net_BINARY_DIR}/include/SDL2")
    target_include_directories(SDL2_net INTERFACE "${sdl2_net_BINARY_DIR}/include")
endif()

# ============================== Ogg and Vorbis ==============================
# The audio backend links Ogg::ogg and Vorbis::* on every platform, but the
# branch of the platform switch that Android falls into does its find_package
# against a system install that the NDK has no equivalent of.
find_package(Ogg QUIET)
if(NOT Ogg_FOUND)
    FetchContent_Declare(
        Ogg
        GIT_REPOSITORY https://github.com/xiph/ogg.git
        GIT_TAG v1.3.5
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Ogg)
endif()

find_package(Vorbis QUIET)
if(NOT Vorbis_FOUND)
    FetchContent_Declare(
        Vorbis
        GIT_REPOSITORY https://github.com/xiph/vorbis.git
        GIT_TAG v1.3.7
        GIT_SHALLOW TRUE
        OVERRIDE_FIND_PACKAGE
    )
    FetchContent_MakeAvailable(Vorbis)

    # Unlike ogg, which aliases Ogg::ogg in its own CMakeLists, vorbis only
    # defines the Vorbis:: names in the config package it installs. Built as a
    # subproject that install never runs for, so the callers linking
    # Vorbis::vorbis would fail on a target that does not exist.
    foreach(component vorbis vorbisenc vorbisfile)
        if(TARGET ${component} AND NOT TARGET Vorbis::${component})
            add_library(Vorbis::${component} ALIAS ${component})
        endif()
    endforeach()
endif()

# ============================ libultraship patch ============================
# arm64 top-byte-ignore fix, not upstream yet (Kenix3/libultraship#1214). Written
# by izzy2lost. Drop this block and the patch file once it lands in the pin.
set(_lh_lus_patch ${CMAKE_CURRENT_SOURCE_DIR}/android/patches/libultraship-arm64-tbi.patch)
execute_process(
    COMMAND git apply --check --reverse ${_lh_lus_patch}
    WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/libultraship
    RESULT_VARIABLE _lh_lus_patched
    OUTPUT_QUIET ERROR_QUIET
)
if(NOT _lh_lus_patched EQUAL 0)
    execute_process(
        COMMAND git apply ${_lh_lus_patch}
        WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/libultraship
        RESULT_VARIABLE _lh_lus_result
    )
    if(NOT _lh_lus_result EQUAL 0)
        message(FATAL_ERROR "Could not apply ${_lh_lus_patch} to libultraship")
    endif()
endif()
