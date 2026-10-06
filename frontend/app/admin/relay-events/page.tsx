'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

/**
 * The relay events used to be one page. They are two now - the form class relays
 * and the grade house relays - so this path hands a bookmark or an old link to the
 * first of them, which carries the tab to the other.
 */
export default function RelayEventsPage() {
  const router = useRouter();

  useEffect(() => {
    router.replace('/admin/relay-events/form');
  }, [router]);

  return null;
}
