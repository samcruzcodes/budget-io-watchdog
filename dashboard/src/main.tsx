import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { LiveStreamProvider } from './LiveStream';
import './styles.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <LiveStreamProvider>
      <App />
    </LiveStreamProvider>
  </StrictMode>,
);
