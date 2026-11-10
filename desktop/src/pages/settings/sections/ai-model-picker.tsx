import { useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'
import { m } from '~/paraglide/messages.js'
import { cn } from '~/lib/style'
import type { AiModelBadge, AiModelOption } from '~/lib/ai/models'
import { Input } from '~/components/ui/input'
import { Popover, PopoverAnchor, PopoverContent } from '~/components/ui/popover'
import { rowControlClass } from './shared'

const badgeLabel: Record<AiModelBadge, () => string> = {
	free: m.aiModelBadgeFree,
	lowCost: m.aiModelBadgeLowCost,
	local: m.aiModelBadgeLocal,
}

/** A model id field with a ready-made list under it. Typing any other id still works. */
export function AiModelPicker({
	value,
	onChange,
	options,
	placeholder,
}: {
	value: string
	onChange: (model: string) => void
	options: AiModelOption[]
	placeholder: string
}) {
	const [open, setOpen] = useState(false)

	return (
		<Popover open={open && options.length > 0} onOpenChange={setOpen}>
			<PopoverAnchor asChild>
				<div className="relative w-64">
					<Input
						value={value}
						onChange={(e) => onChange(e.target.value)}
						onKeyDown={(e) => e.key === 'ArrowDown' && setOpen(true)}
						placeholder={placeholder}
						spellCheck={false}
						className={cn(rowControlClass, options.length > 0 && 'pe-9')}
						aria-label={m.llmModel()}
					/>
					{options.length > 0 && (
						<button
							type="button"
							onClick={() => setOpen(!open)}
							aria-label={m.aiChooseModel()}
							aria-expanded={open}
							className="absolute inset-y-0 end-0 flex w-9 cursor-pointer items-center justify-center text-muted-foreground hover:text-foreground">
							<ChevronDown className={cn('h-4 w-4 transition-transform duration-150', open && 'rotate-180')} />
						</button>
					)}
				</div>
			</PopoverAnchor>
			<PopoverContent align="end" className="w-72 p-1" onOpenAutoFocus={(e) => e.preventDefault()}>
				<div role="listbox" aria-label={m.aiChooseModel()} className="max-h-72 overflow-y-auto">
					{options.map((option) => (
						<button
							key={option.id}
							type="button"
							role="option"
							aria-selected={option.id === value}
							onClick={() => {
								onChange(option.id)
								setOpen(false)
							}}
							className="flex w-full cursor-pointer items-center gap-2 rounded-md px-2.5 py-2 text-start text-[13px] hover:bg-muted focus-visible:bg-muted focus-visible:outline-none">
							<span className="min-w-0 flex-1">
								<span className="block truncate font-medium text-foreground">{option.name}</span>
								{option.name !== option.id && <span className="block truncate font-mono text-[11px] text-muted-foreground">{option.id}</span>}
							</span>
							{option.badge && (
								<span className="shrink-0 rounded-full bg-success/12 px-2 py-0.5 text-[11px] font-medium text-success">
									{badgeLabel[option.badge]()}
								</span>
							)}
							<Check className={cn('h-4 w-4 shrink-0 text-foreground', option.id !== value && 'invisible')} />
						</button>
					))}
				</div>
				<p className="border-t border-border/60 px-2.5 pt-2 pb-1.5 text-[11px] text-muted-foreground">{m.aiModelTypeAny()}</p>
			</PopoverContent>
		</Popover>
	)
}
