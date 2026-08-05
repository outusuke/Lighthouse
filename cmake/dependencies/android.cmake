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
