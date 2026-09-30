package dev.mateuy.panoptes.ui

import dev.mateuy.panoptes.application.CREDENTIAL_FIELDS
import dev.mateuy.panoptes.application.CredentialField
import dev.mateuy.panoptes.application.PromoteBuildUseCase
import dev.mateuy.panoptes.application.StoreResult
import dev.mateuy.panoptes.application.ViewVersionsUseCase
import dev.mateuy.panoptes.application.canEdit
import dev.mateuy.panoptes.application.currentValue
import dev.mateuy.panoptes.application.saveSettings
import dev.mateuy.panoptes.application.suggestedValue
import dev.mateuy.panoptes.domain.model.ReleaseStatus
import dev.mateuy.panoptes.domain.model.Track
import dev.mateuy.panoptes.domain.model.TrackVersion
import dev.mateuy.panoptes.domain.port.CredentialStore
import dev.mateuy.panoptes.domain.port.StoreAdapter
import dev.mateuy.panoptes.infrastructure.ConfigReader
import dev.mateuy.panoptes.infrastructure.ENV_CREDENTIALS
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File

private const val RESET = "\u001B[0m"
private const val BOLD = "\u001B[1m"
private const val DIM = "\u001B[2m"
private const val RED = "\u001B[31m"
private const val GREEN = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val CYAN = "\u001B[36m"

fun runCli(
    configReader: ConfigReader,
    credentialStore: CredentialStore,
    adapters: List<StoreAdapter>,
    viewVersionsUseCase: ViewVersionsUseCase,
    promoteBuildUseCase: PromoteBuildUseCase,
) {
    if (configReader.isFirstRun) {
        println("${YELLOW}First run detected — launching settings wizard.$RESET")
        runSettingsWizard(credentialStore, configReader)
    }

    while (true) {
        println()
        println("${BOLD}${CYAN}Panoptes$RESET — Release Dashboard")
        println("  [1] View versions   [2] Promote   [3] Settings   [q] Quit")
        print("> ")

        when (readLine()?.trim()) {
            "1" -> runDashboard(viewVersionsUseCase)
            "2" -> runPromoteFlow(adapters, promoteBuildUseCase)
            "3" -> runSettingsWizard(credentialStore, configReader)
            "q", "Q", "quit", "exit", null -> {
                println("Goodbye.")
                break
            }
            else -> println("Unknown option.")
        }
    }
}

// ── Dashboard ────────────────────────────────────────────────────────────────

fun runDashboard(useCase: ViewVersionsUseCase) {
    println("\nFetching versions…")
    val results = runBlocking { useCase.execute().toList() }
        .groupBy { it.storeName }
        .mapValues { (_, list) -> list.last() } // last emission = final state

    val tracks = Track.entries
    val colW = 22
    val storeW = 18

    println()
    print(pad("Store", storeW))
    tracks.forEach { print(pad(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, colW)) }
    println()
    println("─".repeat(storeW + colW * tracks.size))

    results.values.forEach { result ->
        print(pad(result.storeName, storeW))
        when {
            result.isLoading -> print("${YELLOW}loading…$RESET")
            result.error != null -> print("${RED}error: ${result.error.take(120)}$RESET")
            else -> tracks.forEach { track ->
                val v = result.versions?.find { it.track == track }
                print(pad(formatCell(v), colW))
            }
        }
        println()
    }

    val notes = results.values.flatMap { r ->
        r.versions.orEmpty().mapNotNull { v -> v.note?.let { Triple(r.storeName, v.track, it) } }
    }
    if (notes.isNotEmpty()) {
        println()
        notes.forEach { (store, track, note) -> println("${YELLOW}!$RESET $store ${track.name.lowercase()}: $note") }
    }
}

private fun formatCell(v: TrackVersion?): String {
    if (v == null) return "—"
    val statusLabel = when (v.status) {
        ReleaseStatus.PUBLISHED -> "${GREEN}live$RESET"
        ReleaseStatus.IN_REVIEW -> "${YELLOW}review$RESET"
        ReleaseStatus.DRAFT -> "${DIM}draft$RESET"
        ReleaseStatus.HALTED -> "${RED}halted$RESET"
        ReleaseStatus.UNKNOWN -> "?"
    }
    return "${v.versionName} ($statusLabel)"
}

// ── Promote ──────────────────────────────────────────────────────────────────

fun runPromoteFlow(adapters: List<StoreAdapter>, useCase: PromoteBuildUseCase) {
    // Select store
    println("\nSelect store:")
    adapters.forEachIndexed { i, a -> println("  [${i + 1}] ${a.storeName}") }
    println("  [0] Cancel")
    val storeIdx = promptInt(1, adapters.size) ?: return
    val adapter = adapters[storeIdx - 1]

    // Select source track (only promotable ones)
    val promotable = adapter.supportedTracks.filter { adapter.promotionTarget(it) != null }
    if (promotable.isEmpty()) {
        println("${YELLOW}No promotable tracks for ${adapter.storeName}.$RESET")
        return
    }

    println("\nSelect source track (${adapter.storeName}):")
    promotable.forEachIndexed { i, t -> println("  [${i + 1}] ${t.name} → ${adapter.promotionTarget(t)?.name}") }
    println("  [0] Cancel")
    val trackIdx = promptInt(1, promotable.size) ?: return
    val fromTrack = promotable[trackIdx - 1]

    // Confirm
    println("\nPromote ${adapter.storeName}: ${fromTrack.name} → ${adapter.promotionTarget(fromTrack)?.name}")
    print("Confirm? [y/N] ")
    if (readLine()?.trim()?.lowercase() != "y") {
        println("Cancelled.")
        return
    }

    print("Promoting… ")
    val result = runBlocking { useCase.execute(adapter.storeName, fromTrack) }
    if (result.isSuccess) {
        println("${GREEN}Done!$RESET")
    } else {
        println("${RED}Failed: ${result.exceptionOrNull()?.message}$RESET")
    }
}

// ── Settings wizard ──────────────────────────────────────────────────────────

fun runSettingsWizard(credentialStore: CredentialStore, configReader: ConfigReader) {
    println("\n${BOLD}Settings Wizard$RESET  (press Enter to keep current value, type 'skip' to skip a field)\n")
    if (credentialStore.isReadOnly) println("${DIM}Credentials come from .env; only the app ids can be changed here.$RESET\n")

    val values = mutableMapOf<CredentialField, String>()
    CREDENTIAL_FIELDS.filter { credentialStore.canEdit(it) }.forEach { field ->
        val current = currentValue(field, credentialStore, configReader)
        val hint = if (field.hint.isNotEmpty()) " (e.g. ${field.hint})" else ""
        val currentHint = if (current != null) {
            val preview = when (field.kind) {
                CredentialField.Kind.TEXT -> current.take(60)
                CredentialField.Kind.SECRET -> "****"
                CredentialField.Kind.FILE -> "loaded"
            }
            " [current: $preview]"
        } else ""
        // Offered when unset; Enter accepts it
        val suggestion = if (current == null) suggestedValue(field, credentialStore) else null
        val suggestionHint = suggestion?.let { " [from .env: $it]" }.orEmpty()

        print("${field.label}$hint$currentHint$suggestionHint: ")
        val input = readLine()?.trim() ?: return@forEach

        when {
            input == "skip" -> return@forEach
            input.isEmpty() && suggestion != null -> values[field] = suggestion
            input.isEmpty() -> return@forEach // keep existing, or leave unset
            field.kind == CredentialField.Kind.FILE -> {
                val file = File(input)
                if (!file.exists()) {
                    println("  ${RED}File not found — skipping.$RESET")
                    return@forEach
                }
                values[field] = file.readText()
            }
            else -> values[field] = input
        }
    }

    saveSettings(values, credentialStore, configReader)

    println("\n${GREEN}Settings saved.$RESET")
}

// ── Import from environment variables ────────────────────────────────────────

fun importFromEnv(credentialStore: CredentialStore, configReader: ConfigReader) {
    println("\n${BOLD}Importing credentials from environment variables…$RESET\n")

    val env = System.getenv()
    val found = ENV_CREDENTIALS.filter { it.envVar in env }
    if (found.isEmpty()) {
        println("${YELLOW}No matching environment variables found. Did you run with dotenvx?$RESET")
        println("Usage: dotenvx run -- java -jar panoptes.jar --import-env")
        return
    }

    found.forEach { credential ->
        credentialStore.set(credential.storeKey, credential.credKey, credential.decode(env.getValue(credential.envVar)))
        println("  ${GREEN}✓$RESET ${credential.envVar} → ${credential.storeKey}.${credential.credKey}")
    }
    credentialStore.save()
    println("\n${GREEN}Imported ${found.size} credential(s).$RESET")

    val bundleId = env["BUNDLE_ID"]
    if (bundleId != null) {
        val config = configReader.load()
        configReader.save(config.copy(app = config.app.copy(appStoreBundleId = bundleId)))
        println("${GREEN}✓$RESET BUNDLE_ID saved to config.toml (appStoreBundleId)")
    }

    println("\n${YELLOW}Still needed (not in .env):$RESET")
    println("  - Google Play package name")
    println("  - Microsoft Store product ID")
    println("  - Snap name")
    println("Run Panoptes normally and choose [3] Settings to complete these.")
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun promptInt(min: Int, max: Int): Int? {
    print("Enter number ($min–$max, 0=cancel): ")
    val n = readLine()?.trim()?.toIntOrNull() ?: return null
    if (n == 0) return null
    if (n < min || n > max) { println("Out of range."); return null }
    return n
}

private fun pad(s: String, width: Int): String {
    // Strip ANSI codes when measuring length
    val visible = s.replace(Regex("\u001B\\[[0-9;]*m"), "")
    val padding = (width - visible.length).coerceAtLeast(0)
    return s + " ".repeat(padding)
}
