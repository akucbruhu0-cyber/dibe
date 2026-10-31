import * as dialog from '@tauri-apps/plugin-dialog'
import { useState } from 'react'
import { toast } from 'sonner'
import { m } from '~/paraglide/messages.js'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '~/components/ui/dialog'
import { Button } from '~/components/ui/button'
import { Spinner } from '~/components/ui/spinner'
import { Textarea } from '~/components/ui/textarea'
import { FileText, ImageIcon, Paperclip, Sparkles, X } from 'lucide-react'
import {
	ATTACHMENT_EXTENSIONS,
	MAX_ATTACHMENT_MB,
	PLACEHOLDERS,
	attachmentKind,
	attachmentTooLarge,
	canRead,
	fileName,
	presets,
	readAttachment,
	type Attachment,
} from '~/lib/ai'
import { presetLabel } from '~/pages/settings/sections/ai'
import { usePreferenceProvider } from '~/providers/preference'

interface ResummarizeDialogProps {
	onSubmit: (prompt: string, files: Attachment[]) => void
	loading: boolean
	/** Controlled mode: opened from somewhere else (a menu item), so no trigger is rendered. */
	open?: boolean
	onOpenChange?: (open: boolean) => void
}

/**
 * A one-off prompt for this transcript: starts from the prompt in settings, with the presets
 * a click away. Nothing here changes settings; that page is the place for the default. Files
 * attached here go with this one request and are dropped when the dialog closes.
 */
export default function ResummarizeDialog({ onSubmit, loading, open: openProp, onOpenChange }: ResummarizeDialogProps) {
	const { ai } = usePreferenceProvider()
	const summaryPresets = presets.filter((preset) => preset.task === 'summary')
	const [selected, setSelected] = useState<string>(ai.tasks.summary.preset)
	const [prompt, setPrompt] = useState(ai.tasks.summary.prompt)
	const [files, setFiles] = useState<Attachment[]>([])
	const [reading, setReading] = useState(false)
	const [openState, setOpenState] = useState(false)
	const controlled = openProp !== undefined
	const open = controlled ? openProp : openState
	const setOpen = (next: boolean) => {
		if (!next) setFiles([])
		if (controlled) onOpenChange?.(next)
		else setOpenState(next)
	}

	const isValid = prompt.includes(PLACEHOLDERS.transcript) || prompt.includes('%s')

	function handleSubmit() {
		if (!isValid || reading) return
		onSubmit(prompt, files)
		setOpen(false)
	}

	// Each file is checked before it is read, so a PDF the provider cannot open or a huge file
	// costs nothing, and one bad file does not stop the rest.
	async function attach() {
		const picked = await dialog.open({ multiple: true, filters: [{ name: m.attachFiles(), extensions: ATTACHMENT_EXTENSIONS }] })
		if (!picked) return
		setReading(true)
		try {
			for (const path of Array.isArray(picked) ? picked : [picked]) {
				const name = fileName(path)
				const kind = attachmentKind(path)
				try {
					if (!kind) toast.error(m.attachmentUnsupported({ name }))
					else if (!canRead(ai.connection.platform, kind)) toast.error(m.attachmentPdfUnsupported())
					else if (await attachmentTooLarge(path)) toast.error(m.attachmentTooLarge({ name, limit: String(MAX_ATTACHMENT_MB) }))
					else {
						const file = await readAttachment(path, kind)
						setFiles((previous) => [...previous, file])
					}
				} catch (error) {
					toast.error(m.attachmentReadFailed({ name }), { description: String(error) })
				}
			}
		} finally {
			setReading(false)
		}
	}

	return (
		<Dialog open={open} onOpenChange={setOpen}>
			{!controlled && (
				<DialogTrigger asChild>
					<Button variant="ghost" size="icon" className="h-8 w-8 text-muted-foreground hover:text-foreground" disabled={loading}>
						<Sparkles className="h-4 w-4" />
					</Button>
				</DialogTrigger>
			)}
			<DialogContent className="max-w-lg rounded-2xl border-border/60 bg-card/95 p-6 shadow-xl">
				<DialogHeader>
					<DialogTitle className="text-lg font-semibold">{m.customizeSummary()}</DialogTitle>
				</DialogHeader>
				<div className="space-y-4 pt-2">
					<div className="flex flex-wrap gap-1.5">
						{summaryPresets.map((preset) => (
							<button
								key={preset.id}
								type="button"
								onClick={() => {
									setSelected(preset.id)
									setPrompt(preset.prompt)
								}}
								className={`cursor-pointer rounded-full border px-2.5 py-1 text-xs font-medium transition-colors ${
									selected === preset.id
										? 'border-primary bg-primary/10 text-primary'
										: 'border-border/65 bg-background/50 text-muted-foreground hover:bg-accent/40'
								}`}>
								{presetLabel(preset.id)}
							</button>
						))}
					</div>

					<div className="space-y-1.5">
						<Textarea
							value={prompt}
							onChange={(e) => {
								setPrompt(e.target.value)
								setSelected('custom')
							}}
							spellCheck={false}
							className="max-h-[360px] min-h-[160px] font-mono text-[12px] leading-relaxed"
						/>
						{!isValid && <p className="text-xs text-destructive">{m.promptMustContainPlaceholder({ placeholder: PLACEHOLDERS.transcript })}</p>}
					</div>

					<div className="flex flex-wrap items-center gap-1.5">
						{files.map((file, index) => (
							<span
								key={index}
								className="inline-flex max-w-full items-center gap-1.5 rounded-full border border-border/65 bg-background/50 py-1 ps-2.5 pe-1 text-xs text-foreground">
								{file.kind === 'image' ? (
									<ImageIcon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
								) : (
									<FileText className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
								)}
								{/* File names are often Latin inside a Hebrew window; let each pick its own direction. */}
								<span dir="auto" className="max-w-[14rem] truncate">
									{file.name}
								</span>
								<button
									type="button"
									onClick={() => setFiles((previous) => previous.filter((_, position) => position !== index))}
									aria-label={m.removeAttachment({ name: file.name })}
									className="cursor-pointer rounded-full p-0.5 text-muted-foreground transition-colors hover:bg-accent/60 hover:text-foreground">
									<X className="h-3 w-3" />
								</button>
							</span>
						))}
						<Button
							type="button"
							variant="ghost"
							size="sm"
							onClick={() => void attach()}
							disabled={reading}
							className="h-7 rounded-full px-2.5 text-xs text-muted-foreground hover:text-foreground">
							{reading ? <Spinner className="h-3.5 w-3.5" /> : <Paperclip className="h-3.5 w-3.5" />}
							{m.attachFiles()}
						</Button>
					</div>

					<Button onMouseDown={handleSubmit} disabled={!isValid || loading || reading} className="w-full">
						{loading ? m.summarizeLoading() : m.summarizeTranscript()}
					</Button>
				</div>
			</DialogContent>
		</Dialog>
	)
}
