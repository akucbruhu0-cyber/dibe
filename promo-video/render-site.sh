#!/bin/zsh
# Renders the website animations (src/site) to ../site/media: <id>[-<lang>].mp4 (H.264), .webm (VP9), .poster.webp.
# Usage: ./render-site.sh [Id ...]   (default: all). Masters go to a temp dir, then ffmpeg makes the small web files.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../site/media
TMP=$(mktemp -d)
mkdir -p "$OUT"

# id:langs(1=he+en,0=single):posterFrame
ALL=(SiteHero:1:150 SiteFeatLive:1:90 SiteFeatSources:0:60 SiteFeatAi:1:158 SiteFeatChart:1:128 SiteFeatExport:0:100
     SiteFeatPhone:1:90 SiteShow1:1:306 SiteShow2:1:239 SiteShow3:1:299 SitePrivacy:1:140 SiteWordmark:0:35)
WANT=("$@")

web() { # $1 master, $2 out base (no ext)
  ffmpeg -v error -y -i "$1" -an -c:v libx264 -preset slow -crf 28 -pix_fmt yuv420p -movflags +faststart "$2.mp4"
  ffmpeg -v error -y -i "$1" -an -c:v libvpx-vp9 -crf 38 -b:v 0 -row-mt 1 -deadline good -cpu-used 2 -pix_fmt yuv420p "$2.webm"
}

for spec in $ALL; do
  id=${spec%%:*}; rest=${spec#*:}; langs=${rest%%:*}; poster=${rest#*:}
  if (( ${#WANT} )) && [[ ${WANT[(Ie)$id]} -eq 0 ]]; then continue; fi
  if [[ $id == SiteWordmark ]]; then
    # transparent: ProRes 4444 master → VP9 with alpha; the MP4 sits on Paper (H.264 has no alpha)
    npx remotion render $id "$TMP/$id.mov" --codec=prores --prores-profile=4444 --image-format=png --pixel-format=yuva444p10le --muted --log=error
    ffmpeg -v error -y -i "$TMP/$id.mov" -an -c:v libvpx-vp9 -pix_fmt yuva420p -crf 30 -b:v 0 -auto-alt-ref 0 "$OUT/$id.webm"
    ffmpeg -v error -y -f lavfi -i color=c=0xF5FAF6:s=256x256:r=30 -i "$TMP/$id.mov" -filter_complex "[0][1]overlay=shortest=1" -an -c:v libx264 -crf 28 -pix_fmt yuv420p -movflags +faststart "$OUT/$id.mp4"
    npx remotion still $id "$TMP/$id.png" --frame=$poster --log=error
    cwebp -quiet -lossless "$TMP/$id.png" -o "$OUT/$id.poster.webp"
    continue
  fi
  for lang in he en; do
    if [[ $langs == 0 && $lang == en ]]; then continue; fi
    comp=$id; base="$OUT/$id"
    if [[ $langs == 1 ]]; then comp=$id${(C)lang}; base="$OUT/$id-$lang"; fi
    echo "→ $comp"
    npx remotion render $comp "$TMP/$comp.mp4" --codec=h264 --crf=14 --muted --log=error
    web "$TMP/$comp.mp4" "$base"
    npx remotion still $comp "$TMP/$comp.png" --frame=$poster --log=error
    cwebp -quiet -q 82 -m 6 "$TMP/$comp.png" -o "$base.poster.webp"
  done
done
rm -rf "$TMP"
