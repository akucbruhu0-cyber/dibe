// One import point for scene files.
export * from "../ui";
export { ClipWindow } from "../clip";
import React from "react";
import { COL_W, COL_X } from "../theme";
import { useDir } from "../i18n";

/** The centered column — everything essential goes in here. */
export const Col: React.FC<{ top: number; children: React.ReactNode; gap?: number; align?: string }> = ({ top, children, gap = 0, align = "center" }) => {
  const dir = useDir();
  return React.createElement(
    "div",
    { style: { position: "absolute", top, left: COL_X, width: COL_W, display: "flex", flexDirection: "column", alignItems: align, gap, direction: dir } },
    children,
  );
};
