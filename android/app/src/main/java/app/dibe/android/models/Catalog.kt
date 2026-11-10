package app.dibe.android.models

private const val HF = "https://huggingface.co"

internal const val WHISPER_BASE = "whisper-base-q8_0"
internal const val WHISPER_SMALL = "whisper-small-q8_0"
internal const val PARAKEET_HE = "parakeet-tdt-0.6b-v3-he-q8_0"
internal const val PARAKEET_MULTI = "parakeet-tdt-0.6b-v3-q4_k_m"
internal const val WHISPER_TURBO = "whisper-large-v3-turbo-q5_0"

/** Sizes and sha256 are the Hugging Face LFS entries of each file. */
internal val CATALOG: List<ModelSpec> = listOf(
    ModelSpec(
        id = WHISPER_BASE, name = "Whisper Base", tier = Tier.LIGHT, engine = EngineKind.WHISPER,
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-base-q8_0.bin", fileName = "ggml-base-q8_0.bin",
        sizeBytes = 81_768_585, sha256 = "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9",
        minRamGb = 3, live = true,
    ),
    ModelSpec(
        id = WHISPER_SMALL, name = "Whisper Small", tier = Tier.LIGHT, engine = EngineKind.WHISPER,
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin", fileName = "ggml-small-q8_0.bin",
        sizeBytes = 264_464_607, sha256 = "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f",
        minRamGb = 4, live = true,
    ),
    ModelSpec(
        id = PARAKEET_HE, name = "Parakeet Hebrew", tier = Tier.MEDIUM, engine = EngineKind.PARAKEET,
        url = "$HF/vibe-app/parakeet-tdt-0.6b-v3-he-gguf/resolve/main/parakeet-tdt-0.6b-v3-he-q8_0.gguf",
        fileName = "parakeet-tdt-0.6b-v3-he-q8_0.gguf",
        sizeBytes = 758_224_288, sha256 = "37a6787344617fd7402397cd7ec4dd4f4bda779cb3112ac571e7b3329194e8dd",
        minRamGb = 6, live = true, hebrew = true,
    ),
    // The repo publishes only Q4_K_M.
    ModelSpec(
        id = PARAKEET_MULTI, name = "Parakeet Multilingual", tier = Tier.MEDIUM, engine = EngineKind.PARAKEET,
        url = "$HF/vibe-app/parakeet-tdt-0.6b-v3-gguf/resolve/main/parakeet-tdt-0.6b-v3-Q4_K_M.gguf",
        fileName = "parakeet-tdt-0.6b-v3-Q4_K_M.gguf",
        sizeBytes = 485_425_536, sha256 = "5c61c041314d87ef1ffc278f5fb1a8ea25cceb8ce6053f8b516bab7ba16d7508",
        minRamGb = 6, live = true,
    ),
    // Encodes a 30 s window in ~37 s on a Pixel 9 Pro (docs/android-transcription.md): files only.
    ModelSpec(
        id = WHISPER_TURBO, name = "Whisper Large v3 Turbo", tier = Tier.HEAVY, engine = EngineKind.WHISPER,
        url = "$HF/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        sizeBytes = 574_041_195, sha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        minRamGb = 8, live = false,
    ),
)

/** Every engine needs it; fetched with the first model, never listed in the catalog. */
internal val VAD_SPEC = ModelSpec(
    id = "silero-vad-v6.2.0", name = "Silero VAD", tier = Tier.LIGHT, engine = EngineKind.WHISPER,
    url = "$HF/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin", fileName = "ggml-silero-v6.2.0.bin",
    sizeBytes = 885_098, sha256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987",
    minRamGb = 0, live = true,
)

private const val GIB = 1L shl 30

/** totalMem leaves out kernel-reserved memory (an 8 GB phone reports ~7.3 GiB), so round up to the nominal size. */
internal fun fitFor(totalRamBytes: Long, minRamGb: Int): Fit {
    val nominalGb = (totalRamBytes + GIB - 1) / GIB
    return when {
        nominalGb >= minRamGb + 2 -> Fit.GOOD
        nominalGb >= minRamGb -> Fit.TIGHT
        else -> Fit.TOO_HEAVY
    }
}

/** Android still reports Hebrew as the legacy "iw". */
internal fun isHebrew(language: String): Boolean = language == "iw" || language == "he"

/** Multilingual Parakeet has no Hebrew, so a Hebrew UI falls straight to Whisper. */
internal fun recommend(hebrewUi: Boolean, fit: (ModelSpec) -> Fit): ModelSpec =
    listOf(if (hebrewUi) PARAKEET_HE else PARAKEET_MULTI, WHISPER_SMALL)
        .map(::catalogSpec)
        .firstOrNull { fit(it) != Fit.TOO_HEAVY }
        ?: catalogSpec(WHISPER_BASE)

internal fun catalogSpec(id: String): ModelSpec = requireNotNull(CATALOG.find { it.id == id }) { "Unknown model $id" }
