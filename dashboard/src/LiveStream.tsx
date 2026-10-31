// One connection to /api/stream for the whole app, shared through React context.

import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { api, type Alert, type Transaction } from './api';
import { connected, failed, initialConnection, retryDelayMs, type ConnectionState } from './connection';
import { addAlerts, addTransactions, emptyFeed, type Feed } from './feed';

const REFILL_SIZE = 100;

interface LiveStream {
  feed: Feed;
  connection: ConnectionState;
  loaded: boolean; // the first REST fill has finished
}

const LiveStreamContext = createContext<LiveStream>({ feed: emptyFeed, connection: initialConnection, loaded: false });

export function useLiveStream(): LiveStream {
  return useContext(LiveStreamContext);
}

export function LiveStreamProvider({ children }: { children: ReactNode }) {
  const [feed, setFeed] = useState<Feed>(emptyFeed);
  const [connection, setConnection] = useState<ConnectionState>(initialConnection);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let source: EventSource | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let state = initialConnection;
    const update = (next: ConnectionState) => {
      state = next;
      setConnection(next);
    };

    // The stream has no replay, so whatever happened while it was down is fetched over REST on every (re)connect.
    // Overlaps with the stream are fine: the feed keeps each ID once.
    async function refill() {
      try {
        const [transactions, alerts] = await Promise.all([api.transactions(REFILL_SIZE), api.alerts(REFILL_SIZE)]);
        setFeed((current) => addAlerts(addTransactions(current, transactions), alerts));
      } catch {
        // The connection badge already says the watchdog is unreachable.
      } finally {
        setLoaded(true);
      }
    }

    function open() {
      source = new EventSource('/api/stream');
      source.onopen = () => {
        update(connected());
        void refill();
      };
      source.addEventListener('transaction', (event) => {
        const transaction = JSON.parse((event as MessageEvent<string>).data) as Transaction;
        setFeed((current) => addTransactions(current, [transaction]));
      });
      source.addEventListener('alert', (event) => {
        const alert = JSON.parse((event as MessageEvent<string>).data) as Alert;
        setFeed((current) => addAlerts(current, [alert]));
      });
      source.onerror = () => {
        update(failed(state));
        // While a connection merely drops, the browser retries by itself. When the server answers with an error
        // instead (nginx's 502 while the watchdog is down), the browser gives up, so we try again after a pause.
        if (source?.readyState === EventSource.CLOSED) {
          source.close();
          retryTimer = setTimeout(open, retryDelayMs(state.failures));
        }
      };
    }

    open();
    void refill(); // show recent history right away, even before the stream connects
    return () => {
      clearTimeout(retryTimer);
      source?.close();
    };
  }, []);

  return <LiveStreamContext.Provider value={{ feed, connection, loaded }}>{children}</LiveStreamContext.Provider>;
}
