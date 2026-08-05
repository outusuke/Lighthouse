#include "Spoiler.h"
#include <libultraship/bridge/consolevariablebridge.h>
#include <ship/Context.h>
#include <filesystem>

#include <libultraship/libultra/types.h>
#include "port/UI/cvar_prefixes.h"

std::vector<std::string> Rando::Spoiler::spoilerLogs;

// Resolved on first use rather than at load time. As a namespace-scope global
// this ran during static initialisation, which on Android happens at dlopen —
// before SDL has set up its JNI environment, so Ship::Context aborts the
// process with "CallStaticObjectMethod received NULL jclass". Static because
// Rando.cpp holds a same-named path of its own; two external definitions of it
// were an ODR violation.
static const std::filesystem::path& randomizerFolderPath() {
    static const std::filesystem::path path(Ship::Context::GetPathRelativeToAppDirectory("randomizer", "bk64"));
    return path;
}

void Rando::Spoiler::RefreshSpoilerLogs() {
    Rando::Spoiler::spoilerLogs.clear();

    Rando::Spoiler::spoilerLogs.push_back("Generate New Seed");
    s32 spoilerFileIndex = -1;

    if (!std::filesystem::exists(randomizerFolderPath())) {
        std::filesystem::create_directory(randomizerFolderPath());
    }

    for (const auto& entry : std::filesystem::directory_iterator(randomizerFolderPath())) {
        if (entry.is_regular_file()) {
            std::string fileName = entry.path().filename().string();

            Rando::Spoiler::spoilerLogs.push_back(fileName);

            // Check if the current file is the one set in the cvar
            if (fileName == CVarGetString(CVAR_RANDOMIZER_SETTING("SpoilerFile"), "")) {
                spoilerFileIndex = Rando::Spoiler::spoilerLogs.size() - 1;
            }
        }
    }

    if (spoilerFileIndex == -1) {
        CVarSetInteger(CVAR_RANDOMIZER_SETTING("SpoilerFileIndex"), 0);
        CVarSetString(CVAR_RANDOMIZER_SETTING("SpoilerFile"), "");
    } else {
        CVarSetInteger(CVAR_RANDOMIZER_SETTING("SpoilerFileIndex"), spoilerFileIndex);
    }
}