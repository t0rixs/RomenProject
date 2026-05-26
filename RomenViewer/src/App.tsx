import { useState } from 'react'
import { RecordingList } from './RecordingList'
import { RecordingDetail } from './RecordingDetail'
import { UploadIndicator } from './UploadIndicator'
import './App.css'

function App() {
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [reloadSignal, setReloadSignal] = useState(0)

  return selectedId ? (
    <RecordingDetail recordingId={selectedId} onBack={() => setSelectedId(null)} />
  ) : (
    <RecordingList
      onSelect={setSelectedId}
      reloadSignal={reloadSignal}
      headerSlot={
        <UploadIndicator onUploadFinished={() => setReloadSignal((n) => n + 1)} />
      }
    />
  )
}

export default App
