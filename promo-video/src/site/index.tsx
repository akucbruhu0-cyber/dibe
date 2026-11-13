// Website animations (site/SPEC.md §4). HE/EN variants share identical timing so the page can swap by language.
import React from "react";
import { Composition } from "remotion";
import { LangContext, type Lang } from "../i18n";
import { HERO, SiteFeatLive, SiteFeatPhone, SiteHero, SitePrivacy } from "./LiveLoops";
import { SiteFeatAi, SiteFeatChart, SiteFeatExport, SiteFeatSources } from "./Features";
import { SiteShow1, SiteShow2, SiteShow3, SiteWordmark } from "./Showcase";

type Spec = { id: string; C: React.FC; w: number; h: number; dur: number; langs: boolean };

export const SITE: Spec[] = [
  { id: "SiteHero", C: SiteHero, w: HERO.w, h: HERO.h, dur: HERO.dur, langs: true },
  { id: "SiteFeatLive", C: SiteFeatLive, w: 720, h: 720, dur: 120, langs: true },
  { id: "SiteFeatSources", C: SiteFeatSources, w: 720, h: 720, dur: 120, langs: false },
  { id: "SiteFeatAi", C: SiteFeatAi, w: 720, h: 720, dur: 180, langs: true },
  { id: "SiteFeatChart", C: SiteFeatChart, w: 720, h: 720, dur: 150, langs: true },
  { id: "SiteFeatExport", C: SiteFeatExport, w: 720, h: 720, dur: 120, langs: false },
  { id: "SiteFeatPhone", C: SiteFeatPhone, w: 720, h: 720, dur: 150, langs: true },
  { id: "SiteShow1", C: SiteShow1, w: 1920, h: 1080, dur: 360, langs: true },
  { id: "SiteShow2", C: SiteShow2, w: 1920, h: 1080, dur: 240, langs: true },
  { id: "SiteShow3", C: SiteShow3, w: 1920, h: 1080, dur: 300, langs: true },
  { id: "SitePrivacy", C: SitePrivacy, w: 900, h: 900, dur: 210, langs: true },
  { id: "SiteWordmark", C: SiteWordmark, w: 256, h: 256, dur: 36, langs: false },
];

const withLang = (C: React.FC, lang: Lang): React.FC => () => (
  <LangContext.Provider value={lang}>
    <C />
  </LangContext.Provider>
);

export const SiteCompositions: React.FC = () => (
  <>
    {SITE.flatMap(({ id, C, w, h, dur, langs }) =>
      (langs ? (["he", "en"] as Lang[]) : (["he"] as Lang[])).map((lang) => (
        <Composition key={id + lang} id={langs ? id + (lang === "he" ? "He" : "En") : id} component={withLang(C, lang)} durationInFrames={dur} fps={30} width={w} height={h} />
      )),
    )}
  </>
);
