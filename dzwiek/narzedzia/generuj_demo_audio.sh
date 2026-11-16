#!/usr/bin/env bash
# Generuje krótkie dźwięki demo (syntezowane, bez praw autorskich) do testów strony.
# Wymaga ffmpeg z libopus. Uruchom z katalogu dzwiek/: bash narzedzia/generuj_demo_audio.sh
set -euo pipefail

OUT="$(dirname "$0")/../web/audio"
mkdir -p "$OUT/muzyka" "$OUT/efekty"
SR=48000

# nuta z miękkim atakiem i wybrzmieniem, odpalana co $2 sekund (bez trzasków na granicy)
env_note() { echo "(1-exp(-60*mod(t,$1)))*exp(-3*mod(t,$1))"; }

enc() { # enc <nazwa_wyjściowa_bez_rozszerzenia> <wyrażenie> <czas>
  local name="$1" expr="$2" dur="$3"
  ffmpeg -loglevel error -y -f lavfi -i "aevalsrc='$expr':s=$SR:d=$dur" -ac 1 \
    -c:a libopus -b:a 32k "$OUT/$name.ogg"
  ffmpeg -loglevel error -y -f lavfi -i "aevalsrc='$expr':s=$SR:d=$dur" -ac 1 \
    -c:a aac -b:a 48k -movflags +faststart "$OUT/$name.m4a"
}

# Miasto: spokojne arpeggio C-dur nad cichym podkładem, pętla 12 s
N='mod(floor(t*2),4)'
F="if(eq($N,0),262,if(eq($N,1),330,if(eq($N,2),392,523)))"
enc muzyka/demo_miasto "0.30*$(env_note 0.5)*sin(2*PI*$F*t) + 0.07*sin(2*PI*131*t)*(0.7+0.3*sin(2*PI*t/6)) + 0.05*sin(2*PI*196*t)" 12

# Dzicz: niski, molowy dron z wolnym falowaniem, pętla 12 s
enc muzyka/demo_dzicz "0.16*sin(2*PI*110*t)*(0.6+0.4*sin(2*PI*t/4)) + 0.10*sin(2*PI*131*t)*(0.6+0.4*sin(2*PI*t/6+1)) + 0.08*sin(2*PI*165*t)*(0.5+0.5*sin(2*PI*t/3)) + 0.03*sin(2*PI*220*t)" 12

# Boss: szybki puls basu, pętla 8 s
B='mod(floor(t*4),8)'
BF="if(lt($B,4),55,if(lt($B,6),65,49))"
enc muzyka/demo_boss "0.35*$(env_note 0.25)*sin(2*PI*$BF*t) + 0.18*$(env_note 0.25)*sin(2*PI*2*$BF*t) + 0.06*sin(2*PI*220*t)*(0.5+0.5*sin(2*PI*t))" 8

# Dzwon: dwa tony z długim wybrzmieniem
enc efekty/demo_dzwon "(1-exp(-200*t))*exp(-2.5*t)*(0.4*sin(2*PI*880*t)+0.2*sin(2*PI*1320*t)+0.1*sin(2*PI*2210*t))" 1.6

# Ryk: szum z opadającym tonem
enc efekty/demo_ryk "(1-exp(-30*t))*exp(-2.2*t)*(0.35*(random(0)*2-1)*(0.5+0.5*sin(2*PI*30*t)) + 0.3*sin(2*PI*(90-30*t)*t))" 1.4

ls -la "$OUT/muzyka" "$OUT/efekty"
