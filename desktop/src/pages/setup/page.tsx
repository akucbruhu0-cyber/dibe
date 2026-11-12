import { motion } from 'framer-motion'
import { m } from '~/paraglide/messages.js'
import { viewModel } from './view-model'
import { Progress } from '~/components/ui/progress'
import { Spinner } from '~/components/ui/spinner'
import { Button } from '~/components/ui/button'
import { Dialog, DialogContent } from '~/components/ui/dialog'
import { Tooltip, TooltipContent, TooltipTrigger } from '~/components/ui/tooltip'
import { CONFIG_KEYS } from '~/lib/config-keys'
import { usePersisted } from '~/lib/config-store'
import { Intro } from './intro'
import { ChooseModel } from './choose-model'

function App() {
	const vm = viewModel()
	const [onboardingDone, setOnboardingDone] = usePersisted(CONFIG_KEYS.onboardingDone, false)

	if (!vm.downloading && !vm.direct) {
		return (
			// The window may be shorter than the model list: centred when it fits, scrolls when it does not.
			<div className="app-shell-bg flex h-screen flex-col overflow-y-auto px-5 py-10">
				<div className="m-auto flex w-full justify-center">
					{onboardingDone ? (
						<ChooseModel system={vm.system} recommended={vm.recommended} onDownload={vm.downloadIfOnline} onSkip={vm.cancelSetup} />
					) : (
						<Intro onDone={() => setOnboardingDone(true)} />
					)}
				</div>
				<OfflineDialog vm={vm} />
			</div>
		)
	}

	return (
		<div className="app-shell-bg flex min-h-screen items-center justify-center px-5 py-10">
			<motion.div
				initial={{ opacity: 0, y: 8 }}
				animate={{ opacity: 1, y: 0 }}
				transition={{ duration: 0.22, ease: 'easeOut' }}
				className="w-full max-w-lg text-center">
				<p className="eyebrow">{m.setup({ defaultValue: 'Setup' })}</p>
				<h1 className="mt-4 text-balance text-[28px] font-semibold leading-[1.1] tracking-[-0.03em] md:text-[36px]">{m.downloadingModel()}</h1>

				<div className="mt-9 flex flex-col items-center gap-4">
					{vm.downloadProgress > 0 && (
						<>
							<Progress className="progress-aurora h-1.5 w-full max-w-sm bg-muted" value={vm.downloadProgress} />
							<div className="flex items-center gap-3 text-[13px] text-muted-foreground">
								<span className="tabular-nums">{Math.round(vm.downloadProgress)}%</span>
								{!vm?.location?.state?.downloadURL && (
									<>
										<span className="h-1 w-1 rounded-full bg-border" />
										<span>{m.thisHappensOnce()}</span>
									</>
								)}
							</div>
						</>
					)}
					{(vm.downloadProgress === 0 || vm.isOnline === null) && <Spinner className="h-6 w-6 text-muted-foreground" />}
				</div>

				<Tooltip>
					<TooltipTrigger asChild>
						<Button
							variant="ghost"
							size="sm"
							className="mt-8 rounded-full px-4 text-[13px] font-medium text-muted-foreground hover:text-foreground"
							onClick={vm.cancelSetup}>
							{m.cancel()}
						</Button>
					</TooltipTrigger>
					<TooltipContent>{m.infoCancelDownload()}</TooltipContent>
				</Tooltip>
			</motion.div>

			<OfflineDialog vm={vm} />
		</div>
	)
}

function OfflineDialog({ vm }: { vm: ReturnType<typeof viewModel> }) {
	return (
		<Dialog open={vm.isOnline === false}>
			<DialogContent className="rounded-2xl">
				<h2 className="text-center text-[22px] font-semibold tracking-[-0.03em]">{m.noConnection()}</h2>
				<p className="mt-2 text-center text-[13px] leading-relaxed text-muted-foreground">{m.infoManualDownload()}</p>
				<div className="mt-6 flex flex-col justify-center gap-2">
					<Button className="rounded-full" onClick={vm.retry}>
						{m.tryAgain()}
					</Button>
					<Button variant="ghost" size="sm" className="rounded-full text-muted-foreground hover:text-foreground" onClick={vm.cancelSetup}>
						{m.iPreferManualSetup()}
					</Button>
				</div>
			</DialogContent>
		</Dialog>
	)
}

export default App
