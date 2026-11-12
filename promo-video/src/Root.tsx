import React from "react";
import { Composition, Sequence, staticFile } from "remotion";
import { Audio } from "@remotion/media";
import { FPS, H, TOTAL, W } from "./theme";
import { LangContext, type Lang } from "./i18n";
import { SCENE_IDS, seq } from "./timing";
import { Background, Captions, Guides } from "./ui";
import { Intro } from "./scenes/Intro";
import { Live } from "./scenes/Live";
import { Models } from "./scenes/Models";
import { Ai } from "./scenes/Ai";
import { Skills } from "./scenes/Skills";
import { Outro } from "./scenes/Outro";
import { OnboardingPower } from "./onboarding/OnboardingPower";
import { OnboardingHardware } from "./onboarding/OnboardingHardware";
import { OnboardingFit } from "./onboarding/OnboardingFit";
import { OnboardingWelcome, WELCOME_DUR } from "./onboarding/OnboardingWelcome";
import { REEL_DUR, REEL_FPS, REEL_H, REEL_W, StudentReel } from "./reel/StudentReel";
import { AIR_DUR, AIR_FPS, AIR_H, AIR_W, AirplaneReel } from "./airplane/AirplaneReel";
import { GeminiKey, KEY_DUR, KEY_FPS } from "./gemini-key/GeminiKey";

type Props = { lang: Lang; guides: boolean; voiceover: boolean; captions: boolean };

const SCENES = { intro: Intro, live: Live, models: Models, ai: Ai, skills: Skills, outro: Outro };

const Main: React.FC<Props> = ({ lang, guides, voiceover, captions }) => (
  <LangContext.Provider value={lang}>
    <Background />
    {SCENE_IDS.map((id) => {
      const Scene = SCENES[id];
      const { from, dur } = seq(lang, id);
      return (
        <Sequence key={id} from={from} durationInFrames={dur} premountFor={FPS}>
          <Scene />
        </Sequence>
      );
    })}
    {captions && <Captions />}
    {voiceover && <Audio src={staticFile(`vo/${lang}/full.wav`)} />}
    {guides && <Guides />}
  </LangContext.Provider>
);

export const RemotionRoot: React.FC = () => (
  <>
    <Composition id="DibePromoHe" component={Main} durationInFrames={TOTAL} fps={FPS} width={W} height={H} defaultProps={{ lang: "he" as Lang, guides: false, voiceover: true, captions: true }} />
    <Composition id="DibePromoEn" component={Main} durationInFrames={TOTAL} fps={FPS} width={W} height={H} defaultProps={{ lang: "en" as Lang, guides: false, voiceover: true, captions: true }} />
    <Composition id="StudentReel" component={StudentReel} durationInFrames={REEL_DUR} fps={REEL_FPS} width={REEL_W} height={REEL_H} defaultProps={{ withVideo: true }} />
    <Composition id="AirplaneReel" component={AirplaneReel} durationInFrames={AIR_DUR} fps={AIR_FPS} width={AIR_W} height={AIR_H} />
    <Composition id="GeminiKeyDesktop" component={GeminiKey} durationInFrames={KEY_DUR} fps={KEY_FPS} width={1920} height={1080} defaultProps={{ layout: "desktop" as const }} />
    <Composition id="GeminiKeyPhone" component={GeminiKey} durationInFrames={KEY_DUR} fps={KEY_FPS} width={1080} height={1920} defaultProps={{ layout: "phone" as const }} />
    <Composition id="OnboardingWelcome"component={OnboardingWelcome} durationInFrames={WELCOME_DUR} fps={30} width={720} height={720} />
    <Composition id="OnboardingPower" component={OnboardingPower} durationInFrames={120} fps={30} width={720} height={720} />
    <Composition id="OnboardingPowerLaptop" component={OnboardingPower} durationInFrames={120} fps={30} width={720} height={720} defaultProps={{ device: "laptop" as const }} />
    <Composition id="OnboardingHardware" component={OnboardingHardware} durationInFrames={120} fps={30} width={720} height={720} />
    <Composition id="OnboardingFit" component={OnboardingFit} durationInFrames={120} fps={30} width={720} height={720} />
  </>
);
