import { Check, Pencil, SquarePen, Trash2, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { api } from '../../api'
import type { ConversationSummary } from '../../api/types'
import { errorMessage, formatWhen } from '../../lib/format'
import { useT } from '../../lib/i18n'
import { Alert, ConfirmPanel } from '../ui'

/** Assistant > Sohbetler: open, rename or delete past chats. */
export function ConversationList({ conversations, currentId, canEdit, onOpen, onChanged, onDeleted }: {
  conversations: ConversationSummary[]
  currentId: number | null
  canEdit: boolean
  onOpen: (id: number | null) => void
  onChanged: (conversation: ConversationSummary) => void
  onDeleted: (id: number) => void
}) {
  const t = useT()
  const [editing, setEditing] = useState<number | null>(null)
  const [title, setTitle] = useState('')
  const [confirming, setConfirming] = useState<ConversationSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (e) {
      setError(errorMessage(e, t('İşlem başarısız', 'Something went wrong')))
    } finally {
      setBusy(false)
    }
  }

  const rename = (event: FormEvent, id: number) => {
    event.preventDefault()
    if (!title.trim()) return
    void run(async () => {
      onChanged(await api.renameConversation(id, title.trim()))
      setEditing(null)
    })
  }

  if (confirming) {
    return (
      <ConfirmPanel
        text={t(`“${confirming.title ?? 'Yeni sohbet'}” sohbeti ve mesajları kalıcı olarak silinsin mi? Oluşturduğu rota Rotalarım’da kalır.`,
          `Permanently delete the chat “${confirming.title ?? 'New chat'}” and its messages? Its route stays in My routes.`)}
        confirmLabel={t('Sil', 'Delete')} busy={busy} error={error} onCancel={() => setConfirming(null)}
        onConfirm={() => void run(async () => {
          await api.deleteConversation(confirming.id)
          onDeleted(confirming.id)
          setConfirming(null)
        })} />
    )
  }

  return (
    <div className="stack">
      <button className="btn btn-primary btn-block" onClick={() => onOpen(null)}>
        <SquarePen size={18} /> {t('Yeni sohbet', 'New chat')}
      </button>
      {error && <Alert tone="danger"><span>{error}</span></Alert>}
      {conversations.length === 0 ? (
        <p className="t-caption" style={{ textAlign: 'center' }}>{t('Henüz bir sohbetin yok.', 'You have no chats yet.')}</p>
      ) : (
        <div className="list-group">
          {conversations.map(c => editing === c.id ? (
            <form key={c.id} className="list-item" onSubmit={e => rename(e, c.id)}>
              <span className="input grow" style={{ minHeight: 44 }}>
                <input value={title} onChange={e => setTitle(e.target.value)} maxLength={120} autoFocus
                       aria-label={t('Sohbet adı', 'Chat name')} />
              </span>
              <button type="submit" className="icon-btn icon-btn-plain" disabled={busy || !title.trim()} aria-label={t('Kaydet', 'Save')}><Check size={18} /></button>
              <button type="button" className="icon-btn icon-btn-plain" onClick={() => setEditing(null)} aria-label={t('Vazgeç', 'Cancel')}><X size={18} /></button>
            </form>
          ) : (
            <div key={c.id} className="list-item" style={c.id === currentId ? { background: 'var(--brand-50)' } : undefined}>
              <button className="grow" style={{ minWidth: 0, background: 'none', border: 'none', padding: 0, textAlign: 'left', color: 'inherit' }}
                      onClick={() => onOpen(c.id)} aria-current={c.id === currentId ? 'page' : undefined}>
                <strong style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.title ?? t('Yeni sohbet', 'New chat')}
                </strong>
                <span className="t-caption" style={{ display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {c.routeTitle ? `${c.routeTitle} · ` : ''}{c.preview ?? ''}
                </span>
                <span className="t-caption muted">{formatWhen(c.lastMessageAt)}</span>
              </button>
              {canEdit && (
                <>
                  <button className="icon-btn icon-btn-plain" aria-label={t(`“${c.title ?? 'Yeni sohbet'}” adını değiştir`, `Rename “${c.title ?? 'New chat'}”`)}
                          onClick={() => { setEditing(c.id); setTitle(c.title ?? '') }}>
                    <Pencil size={17} />
                  </button>
                  <button className="icon-btn icon-btn-plain" style={{ color: 'var(--danger)' }}
                          aria-label={t(`“${c.title ?? 'Yeni sohbet'}” sohbetini sil`, `Delete “${c.title ?? 'New chat'}”`)}
                          onClick={() => setConfirming(c)}>
                    <Trash2 size={17} />
                  </button>
                </>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
