'use client';

import RelayEventsList from '@/components/RelayEventsList';

/**
 * The **form class relays**: Forms 1 to 6, one team per class, with a filter on
 * each form. The programme's relay events are two families on two pages — this one
 * links to the grade house page beside it.
 */
export default function RelayFormEventsPage() {
  return <RelayEventsList family="FORM" />;
}
