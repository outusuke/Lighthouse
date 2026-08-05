#include "Rando.h"
#include <ship/Context.h>
#include "port/Enhancements/Events/Hooks/Events.h"
#include "ObjectBehavior/ObjectBehavior.h"
#include "MiscBehavior/MiscBehavior.h"
#include "port/Rando/CheckTracker/CheckTracker.h"
// #include "port/Rando/EntranceTracker/EntranceTracker.h"
#include "port/Rando/Spoiler/Spoiler.h"
#include "port/ShipInit.hpp"

#include "spdlog/spdlog.h"

#include <fstream>
#include <filesystem>
namespace fs = std::filesystem;

int16_t selectedFileNum = DEFAULT_FILE_NUM;

// Resolved on first use rather than at load time. As a namespace-scope global
// this ran during static initialisation, which on Android happens at dlopen —
// before SDL has set up its JNI environment, so Ship::Context aborts the
// process with "CallStaticObjectMethod received NULL jclass".
static const fs::path& randomizerFolderPath() {
    static const fs::path path(Ship::Context::GetPathRelativeToAppDirectory("randomizer", "bk64"));
    return path;
}

// Entry point for the module, run once on game boot
void Rando::Init() {
    if (!fs::exists(randomizerFolderPath())) {
        fs::create_directory(randomizerFolderPath());
    }

    Rando::Spoiler::RefreshSpoilerLogs();
    Rando::MiscBehavior::Init();
    // Rando::EntranceTracker::Init();
    // Ship::Context::GetInstance()->GetFileDropMgr()->RegisterDropHandler(Rando::Spoiler::HandleFileDropped);

    REGISTER_LISTENER(InitRandoEvents, EVENT_PRIORITY_NORMAL, [](IEvent* event) {
        InitRandoEvents* ev = (InitRandoEvents*)event;

        Rando::ObjectBehavior::Init();
        Rando::CheckTracker::Init();
    });
}
