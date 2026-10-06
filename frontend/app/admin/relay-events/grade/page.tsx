'use client';

import RelayEventsList from '@/components/RelayEventsList';

/**
 * The **grade house relays**: Grades A to C, one team per grade × house, with a
 * filter on each grade. The programme's relay events are two families on two pages
 * — this one links to the form class page beside it.
 */
export default function RelayHouseEventsPage() {
  return <RelayEventsList family="HOUSE" />;
}
