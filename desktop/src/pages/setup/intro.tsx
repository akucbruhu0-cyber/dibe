import { AnimatePresence, motion, useReducedMotion } from 'framer-motion'
import { useState } from 'react'
import { m } from '~/paraglide/messages.js'
import { cn } from '~/lib/style'
import { Button } from '~/components/ui/button'
import welcomeVideo from '~/assets/onboarding/welcome.mp4'
import welcomePoster from '~/assets/onboarding/welcome.jpg'
import powerVideo from '~/assets/onboarding/power.mp4'
import powerPoster from '~/assets/onboarding/power.jpg'
import hardwareVideo from '~/assets/onboarding/hardware.mp4'
import hardwarePoster from '~/assets/onboarding/hardware.jpg'
import fitVideo from '~/assets/onboarding/fit.mp4'
import fitPoster from '~/assets/onboarding/fit.jpg'

/** Text-free loops rendered from `promo-video/src/onboarding` (`pnpm render:onboarding:desktop`). */
const PAGES = [
	{ video: welcomeVideo, poster: welcomePoster, eyebrow: null, title: m.onbWelcomeTitle, body: m.onbWelcomeBody },
	{ video: powerVideo, poster: powerPoster, eyebrow: m.onbPowerEyebrow, title: m.onbPowerTitle, body: m.onbPowerBody },
	{ video: hardwareVideo, poster: hardwarePoster, eyebrow: m.onbHardwareEyebrow, title: m.onbHardwareTitle, body: m.onbHardwareBody },
	{ video: fitVideo, poster: fitPoster, eyebrow: m.onbFitEyebrow, title: m.onbFitTitle, body: m.onbFitBody },
]

/** First run: a welcome and three things worth knowing about on-device AI, before the model list. */
/** `doneLabel` names the last button when the intro is replayed from Settings. */
export function Intro({ onDone, doneLabel }: { onDone: () => void; doneLabel?: string }) {
	const [page, setPage] = useState(0)
	const reduceMotion = useReducedMotion()
	const current = PAGES[page]
	const last = page === PAGES.length - 1
	// Pages arrive from the reading direction's far side.
	const shift = document.body.dir === 'rtl' ? -24 : 24

	return (
		<div className="flex w-full max-w-md flex-col items-center text-center">
			<AnimatePresence mode="wait" initial={false}>
				<motion.div
					key={page}
					initial={reduceMotion ? { opacity: 0 } : { opacity: 0, x: shift }}
					animate={{ opacity: 1, x: 0 }}
					exit={reduceMotion ? { opacity: 0 } : { opacity: 0, x: -shift }}
					transition={{ duration: 0.2, ease: [0.23, 1, 0.32, 1] }}
					className="flex flex-col items-center">
					{/* Same dark stage in both themes: the loops are rendered on it. */}
					<div className="aspect-square w-64 overflow-hidden rounded-3xl bg-[#05110a] shadow-lg md:w-72">
						{reduceMotion ? (
							<img src={current.poster} alt="" className="h-full w-full object-cover" />
						) : (
							<video
								src={current.video}
								poster={current.poster}
								autoPlay
								loop
								muted
								playsInline
								aria-hidden
								className="h-full w-full object-cover"
							/>
						)}
					</div>
					{current.eyebrow && <p className="eyebrow mt-8">{current.eyebrow()}</p>}
					<h1
						className={cn(
							'text-balance font-semibold tracking-[-0.03em]',
							current.eyebrow ? 'mt-3 text-[24px] leading-[1.15]' : 'mt-8 text-[30px] leading-[1.1] md:text-[34px]',
						)}>
						{current.title()}
					</h1>
					<p className="mt-3 text-pretty text-[14px] leading-relaxed text-muted-foreground">{current.body()}</p>
				</motion.div>
			</AnimatePresence>

			<div className="mt-8 flex gap-1.5" role="img" aria-label={m.onbPageOf({ current: String(page + 1), total: String(PAGES.length) })}>
				{PAGES.map((_, index) => (
					<span key={index} className={cn('h-1.5 rounded-full transition-all duration-200', index === page ? 'w-5 bg-primary-strong' : 'w-1.5 bg-border')} />
				))}
			</div>

			<div className="mt-6 flex w-full max-w-xs flex-col gap-2">
				<Button className="h-11 rounded-full" onClick={() => (last ? onDone() : setPage(page + 1))}>
					{page === 0 ? m.onbLetsStart() : last ? (doneLabel ?? m.onbChooseModel()) : m.onbNext()}
				</Button>
				<div className="flex justify-center gap-1">
					{page > 0 && (
						<Button variant="ghost" size="sm" className="rounded-full text-muted-foreground" onClick={() => setPage(page - 1)}>
							{m.back()}
						</Button>
					)}
					{!last && (
						<Button variant="ghost" size="sm" className="rounded-full text-muted-foreground" onClick={onDone}>
							{m.onbSkipIntro()}
						</Button>
					)}
				</div>
			</div>
		</div>
	)
}
