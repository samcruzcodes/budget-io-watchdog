import { useEffect, useRef, useState } from 'react';

/**
 * The rendered width of an element, kept up to date as the page resizes. Charts draw in real pixels with it, so axis
 * text stays the same size on a phone and a wide screen, instead of a fixed drawing scaled up or down.
 */
export function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return { ref, width };
}
