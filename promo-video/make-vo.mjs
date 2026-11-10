// Generates one narration clip per scene and language with macOS `say`, then prints durations (s).
// Usage: node make-vo.mjs [he|en]   — rate tweak: RATE_HE / RATE_EN env vars (words per minute).
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const narration = JSON.parse(readFileSync(new URL("./src/narration.json", import.meta.url)));
const voices = { he: ["Carmit", Number(process.env.RATE_HE ?? 165)], en: ["Samantha", Number(process.env.RATE_EN ?? 185)] };
const only = process.argv[2];

for (const [lang, scenes] of Object.entries(narration)) {
  if (only && only !== lang) continue;
  const dir = fileURLToPath(new URL(`./public/vo/${lang}/`, import.meta.url)); // not .pathname: it %-encodes Hebrew paths
  mkdirSync(dir, { recursive: true });
  for (const [scene, text] of Object.entries(scenes)) {
    const aiff = `${dir}${scene}.aiff`;
    const mp3 = `${dir}${scene}.mp3`;
    execFileSync("say", ["-v", voices[lang][0], "-r", String(voices[lang][1]), "-o", aiff, text]);
    execFileSync("ffmpeg", ["-y", "-loglevel", "error", "-i", aiff, "-ar", "44100", "-b:a", "128k", mp3]);
    execFileSync("rm", [aiff]);
    const dur = execFileSync("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", mp3]).toString().trim();
    console.log(lang, scene, Number(dur).toFixed(2) + "s");
  }
}
