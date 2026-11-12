import { CheckCircle2, Cpu, MemoryStick, TriangleAlert, XCircle, Gauge } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { m } from '~/paraglide/messages.js'
import { cn } from '~/lib/style'
import { formatBytes } from '~/lib/storage'
import { fitFor, sortForMachine, type CatalogModel, type ModelFit } from '~/lib/model-catalog'
import { Button } from '~/components/ui/button'
import { Spinner } from '~/components/ui/spinner'
import type { SystemInfo } from './view-model'

const languageLabel = { he: m.modelLangHebrew, eu: m.modelLangEuropean, all: m.modelLangAll }
const speedLabel = { fastest: m.modelSpeedFastest, fast: m.modelSpeedFast, medium: m.modelSpeedMedium }
const accuracyLabel = { basic: m.modelAccuracyBasic, good: m.modelAccuracyGood, best: m.modelAccuracyBest }
const fitLabel: Record<ModelFit, () => string> = { good: m.fitGood, tight: m.fitTight, tooHeavy: m.fitTooHeavy }

/** Keeps "758 MB" or "16 GB" in its own direction inside Hebrew text (FSI…PDI). */
const isolate = (text: string) => `\u2068${text}\u2069`

function FitLine({ fit }: { fit: ModelFit }) {
	const Icon = fit === 'good' ? CheckCircle2 : fit === 'tight' ? TriangleAlert : XCircle
	return (
		<span
			className={cn(
				'flex items-center gap-1.5 text-[12px]',
				fit === 'good' ? 'text-success' : fit === 'tight' ? 'text-amber-600 dark:text-amber-400' : 'text-destructive',
			)}>
			<Icon className="h-3.5 w-3.5 shrink-0" />
			{fitLabel[fit]()}
		</span>
	)
}

function Fact({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
	return (
		<div className="flex min-w-0 flex-1 items-center gap-2.5 px-4 py-3">
			<span className="text-muted-foreground">{icon}</span>
			<span className="min-w-0">
				<span className="block text-[11px] text-muted-foreground">{label}</span>
				<span dir="auto" className="block truncate text-[13px] font-medium" title={value}>
					{value}
				</span>
			</span>
		</div>
	)
}

/** Where a first download is chosen: this machine's facts, the model that fits it, and the rest. */
export function ChooseModel({
	system,
	recommended,
	onDownload,
	onSkip,
}: {
	system: SystemInfo | null
	recommended: CatalogModel
	onDownload: (model: CatalogModel) => void
	onSkip: () => void
}) {
	const [selectedId, setSelectedId] = useState<string | null>(null)

	if (!system) return <Spinner className="h-6 w-6 text-muted-foreground" />

	const known = system.total_memory > 0
	const models = sortForMachine(recommended, system.total_memory)
	const selected = models.find((model) => model.id === selectedId) ?? recommended

	return (
		<div className="w-full max-w-xl">
			<div className="text-center">
				<p className="eyebrow">{m.onbDeviceEyebrow()}</p>
				<h1 className="mt-3 text-balance text-[26px] font-semibold leading-[1.15] tracking-[-0.03em]">{m.onbDeviceTitle()}</h1>
				<p className="mt-2 text-[14px] text-muted-foreground">{m.onbDeviceBody()}</p>
			</div>

			<div className="mt-6 flex divide-x divide-border/70 overflow-hidden rounded-2xl bg-card shadow-hairline rtl:divide-x-reverse">
				<Fact icon={<Cpu className="h-4 w-4" />} label={m.onbProcessor()} value={system.cpu_name || m.onbUnknown()} />
				<Fact
					icon={<MemoryStick className="h-4 w-4" />}
					label={m.onbMemory()}
					value={known ? isolate(`${Math.round(system.total_memory / 1024 ** 3)} GB`) : m.onbUnknown()}
				/>
				<Fact icon={<Gauge className="h-4 w-4" />} label={m.onbCores()} value={system.cpu_cores ? String(system.cpu_cores) : m.onbUnknown()} />
			</div>

			<div role="radiogroup" aria-label={m.selectModel()} className="mt-4 space-y-2">
				{models.map((model) => {
					const isSelected = model.id === selected.id
					const isRecommended = model.id === recommended.id
					return (
						<button
							key={model.id}
							type="button"
							role="radio"
							aria-checked={isSelected}
							onClick={() => setSelectedId(model.id)}
							className={cn(
								'flex w-full cursor-pointer items-start gap-3 rounded-2xl border px-4 py-3.5 text-start transition-colors duration-150',
								isSelected ? 'border-primary-strong bg-card ring-1 ring-primary-strong' : 'border-transparent bg-card/70 shadow-hairline hover:bg-card',
							)}>
							<span
								className={cn(
									'mt-1 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border',
									isSelected ? 'border-primary-strong' : 'border-muted-foreground/50',
								)}>
								{isSelected && <span className="h-2 w-2 rounded-full bg-primary-strong" />}
							</span>
							<span className="min-w-0 flex-1">
								<span className="flex flex-wrap items-center gap-2">
									<span className="text-[14px] font-semibold">{model.name}</span>
									{isRecommended && (
										<span className="rounded-full bg-primary px-2 py-0.5 text-[11px] font-medium text-primary-foreground">
											{m.onbRecommended()}
										</span>
									)}
								</span>
								<span className="mt-1 block text-[12px] text-muted-foreground">
									{[
										formatBytes(model.sizeBytes),
										languageLabel[model.languages](),
										speedLabel[model.speed](),
										accuracyLabel[model.accuracy](),
										m.modelNeedsRam({ gb: String(model.minRamGb) }),
									]
										.map(isolate)
										.join(' · ')}
								</span>
								{known && (
									<span className="mt-1.5 block">
										<FitLine fit={fitFor(system.total_memory, model.minRamGb)} />
									</span>
								)}
							</span>
						</button>
					)
				})}
			</div>

			<div className="mt-6 flex flex-col items-center gap-2">
				<Button variant="dark" className="h-11 w-full max-w-xs" onClick={() => onDownload(selected)}>
					{m.onbDownload({ size: isolate(formatBytes(selected.sizeBytes)) })}
				</Button>
				<Button variant="ghost" size="sm" className="rounded-full text-muted-foreground" onClick={onSkip}>
					{m.onbSkipForNow()}
				</Button>
			</div>
		</div>
	)
}
