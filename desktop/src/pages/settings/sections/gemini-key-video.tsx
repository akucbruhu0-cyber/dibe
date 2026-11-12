import { PlayCircle } from 'lucide-react'
import { m } from '~/paraglide/messages.js'
import { Button } from '~/components/ui/button'
import { Dialog, DialogContent, DialogTitle, DialogTrigger } from '~/components/ui/dialog'
import video from '~/assets/tutorials/gemini-key.mp4'
import poster from '~/assets/tutorials/gemini-key.jpg'
import { SettingsRow } from './shared'

/** "How do I get a free key?": the tutorial rendered from `promo-video/src/gemini-key` (`pnpm render:gemini-key`). */
export function GeminiKeyVideo() {
	return (
		<SettingsRow label={m.geminiKeyVideo()} description={m.geminiKeyVideoInfo()}>
			<Dialog>
				<DialogTrigger asChild>
					<Button variant="outline" size="sm" className="rounded-lg">
						<PlayCircle className="h-4 w-4" />
						{m.geminiKeyVideoWatch()}
					</Button>
				</DialogTrigger>
				<DialogContent className="max-w-4xl p-2">
					<DialogTitle className="sr-only">{m.geminiKeyVideo()}</DialogTitle>
					<video src={video} poster={poster} controls autoPlay playsInline className="w-full rounded-lg" />
				</DialogContent>
			</Dialog>
		</SettingsRow>
	)
}
