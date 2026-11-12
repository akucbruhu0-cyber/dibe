import { cn } from '~/lib/style'

/**
 * Pocket's scroll edge: content running down under the bottom of a scroll area softens into it, a
 * 4px backdrop blur faded in by a mask, over a fade to the surface colour. Only the bottom: a top
 * fade would blur the first row before anything scrolled. Put it inside the scroller's `relative`
 * parent, after the scroller, and give the content ~6rem of room at its end.
 * `tint={false}` keeps only the blur, for surfaces that are not the card (the sidebar).
 */
export function EdgeFade({ tint = true }: { tint?: boolean }) {
	return (
		<div
			aria-hidden="true"
			className={cn(
				'pointer-events-none absolute inset-x-0 bottom-0 z-10 h-24 backdrop-blur-[4px] [mask-image:linear-gradient(to_bottom,transparent,#000_55%)]',
				tint && 'bg-gradient-to-t from-card via-card/70 to-transparent',
			)}
		/>
	)
}
