'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useAuth } from '@/lib/auth';
import { usePathname, useRouter } from 'next/navigation';
import { useI18n, MessageKey } from '@/lib/i18n';
import { LanguageSwitch } from '@/components/LanguageSwitch';

/**
 * One link, and who may see it.
 *
 * The permission is carried **with the link** rather than applied where it is
 * rendered, so a link cannot be added to a group and forget its rule. A group is
 * then built by filtering, and a group left with nothing in it is not rendered at
 * all — which is what stops a student being shown four empty menus.
 */
type NavItem = {
  href: string;
  key: MessageKey;
  /** Whether this reader may see it. Exactly the rule the flat menu applied. */
  allowed: boolean;
};

type NavGroup = {
  id: string;
  key: MessageKey;
  items: NavItem[];
};

/**
 * The header, grouped into four dropdowns by **what the reader is doing**.
 *
 * A flat row of fourteen links wrapped onto a second line under the logo on the
 * widths this school actually uses — a 1366-wide classroom projector and a
 * 1024-wide tablet — which moved the header's height around and pushed the
 * page's own headings down. Four menus of two to seven items keep one line.
 *
 * **Every link keeps the access rule it had.** `My Entries` and the language
 * switch and Logout are deliberately *not* in a menu: the switch and Logout are
 * not navigation, and `My Entries` is the one link an athlete uses every day.
 * `Help a student` stays out too — it is a teacher's own day-to-day page, and
 * burying a teacher's main task one level down would be the one regression
 * grouping could cause.
 */
export function Navbar() {
  const { user, logout } = useAuth();
  const { t, label } = useI18n();
  const router = useRouter();
  const pathname = usePathname();
  /** The id of the open group, or null. Only one is ever open. */
  const [open, setOpen] = useState<string | null>(null);
  const barRef = useRef<HTMLElement | null>(null);
  /** Every group's toggle, so Left/Right can step between them. */
  const toggleRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  /** The open group's items, so the arrow keys can walk them. */
  const itemRefs = useRef<Array<HTMLAnchorElement | null>>([]);

  const handleLogout = () => {
    logout();
    router.push('/login');
  };

  const isStaff = user?.role === 'ADMIN' || user?.role === 'MANAGER';
  const isAdmin = user?.role === 'ADMIN';
  /*
   * There is deliberately no `isStudent` here any more. The flat menu rendered the
   * same five links twice, once for a student and once for everybody else, and the
   * two branches were identical apart from the staff items — which the permissions
   * below already carry. Filtering by permission says what was meant without
   * saying it twice.
   */
  /*
   * A teacher holds no administrative power at all: they may enter or withdraw a
   * student in one of their own classes and nothing else. So the teacher pages are
   * shown to ADMIN and TEACHER alike — both may use them — while every `/admin/**`
   * link stays behind `isAdmin`.
   */
  const isTeacher = user?.role === 'TEACHER';
  const canHelpStudents = isAdmin || isTeacher;
  /*
   * An input helper is the narrowest staff role: they key in marks and print
   * marking sheets, and the server refuses them everything else. They are
   * deliberately not `isStaff`, so no other `/admin` link is shown to them.
   */
  const isHelper = user?.role === 'HELPER';

  /**
   * The four groups, each with its items and each item's own rule.
   *
   * Nothing below restates a permission: every `allowed` is the same expression
   * the flat menu used for that same link, so no reader gains or loses a page.
   */
  const groups: NavGroup[] = [
    {
      id: 'setup',
      key: 'nav.groupSetup',
      items: [
        // The programme itself: create the catalogue, edit events, draw groups.
        { href: '/admin/events', key: 'nav.manageEvents', allowed: isAdmin },
        // Setting a required standard is ADMIN-only, exactly like the page.
        { href: '/admin/standards', key: 'nav.standards', allowed: isAdmin },
        // The relay pages serve ADMIN and TEACHER alike — each calls the
        // `/teacher/**` endpoint family for a teacher — so both are shown them. A
        // manager is not, because those pages admit administrators and teachers
        // only. Two links, because the relay programme is two families: the form
        // class relays and the grade house ones, each with its own filter.
        { href: '/admin/relay-events/form', key: 'nav.relayFormEvents', allowed: canHelpStudents },
        { href: '/admin/relay-events/grade', key: 'nav.relayHouseEvents', allowed: canHelpStudents },
      ],
    },
    {
      id: 'run',
      key: 'nav.groupRun',
      items: [
        // A helper keys in marks and prints sheets, and is not staff in the
        // administrative sense; these two are exactly what they may do.
        { href: '/admin/marks', key: 'nav.marks', allowed: isStaff || isHelper },
        { href: '/admin/print', key: 'nav.print', allowed: isStaff || isHelper },
      ],
    },
    {
      id: 'review',
      key: 'nav.groupReview',
      items: [
        // Looking at what happened. Every signed-in role may read these.
        { href: '/results', key: 'nav.results', allowed: true },
        { href: '/records', key: 'nav.records', allowed: true },
        { href: '/championships', key: 'nav.championships', allowed: true },
      ],
    },
    {
      id: 'admin',
      key: 'nav.groupAdmin',
      items: [
        { href: '/admin', key: 'nav.admin', allowed: isStaff && !isHelper },
        // The register. A student is never offered it; a teacher has
        // `Help a student` instead, which is their own page for one class.
        { href: '/admin/students', key: 'nav.students', allowed: isAdmin },
        { href: '/admin/teachers', key: 'nav.teachers', allowed: isAdmin },
        { href: '/admin/users', key: 'nav.users', allowed: isAdmin },
        { href: '/admin/sport-day', key: 'nav.sportDay', allowed: isAdmin },
        { href: '/admin/settings', key: 'nav.settings', allowed: isAdmin },
        { href: '/admin/backups', key: 'nav.backups', allowed: isAdmin },
      ],
    },
  ];

  /* A group with nothing this reader may see is not rendered at all. */
  const visible = groups
    .map(group => ({ ...group, items: group.items.filter(item => item.allowed) }))
    .filter(group => group.items.length > 0);
  const visibleIds = visible.map(group => group.id);

  const close = useCallback(() => setOpen(null), []);

  /**
   * Opens one group and puts focus on its first item.
   *
   * Called from a keypress rather than a click, because that is the difference
   * that matters: a mouse user who clicks the label gets the menu under the
   * pointer, while somebody on the keyboard is carried into it. A click that does
   * **not** move focus means the arrows keep working from wherever focus was.
   */
  const openFromKeyboard = (id: string) => {
    setOpen(id);
    // The menu is rendered by the state change above, so the focus happens after
    // the browser has painted it.
    window.requestAnimationFrame(() => {
      const first = itemRefs.current[0];
      if (first) first.focus();
    });
  };

  /**
   * The keyboard's way through the whole menu, which is why it is one handler
   * rather than one per element:
   *
   *   ArrowDown / ArrowUp   open the group, or walk its items
   *   Home / End            the first or last item
   *   ArrowRight / Left     open the next or previous group and step into it
   *   Escape                close and hand focus back to the group's label
   *   Tab                   close; the browser moves on, so walking never traps
   */
  const onMenuKeyDown = (event: React.KeyboardEvent<HTMLElement>, group: NavGroup) => {
    const items = itemRefs.current;
    const last = group.items.length - 1;
    const current = items.findIndex(item => item === document.activeElement);

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        if (open !== group.id) {
          openFromKeyboard(group.id);
        } else {
          const next = current < 0 ? 0 : Math.min(current + 1, last);
          items[next]?.focus();
        }
        return;
      case 'ArrowUp':
        event.preventDefault();
        if (open !== group.id) {
          openFromKeyboard(group.id);
        } else {
          const previous = current <= 0 ? last : current - 1;
          items[previous]?.focus();
        }
        return;
      case 'Home':
        if (open !== group.id) return;
        event.preventDefault();
        items[0]?.focus();
        return;
      case 'End':
        if (open !== group.id) return;
        event.preventDefault();
        items[last]?.focus();
        return;
      case 'ArrowRight':
      case 'ArrowLeft': {
        const step = event.key === 'ArrowRight' ? 1 : -1;
        const at = visibleIds.indexOf(group.id);
        const nextId = visibleIds[(at + step + visibleIds.length) % visibleIds.length];
        event.preventDefault();
        openFromKeyboard(nextId);
        return;
      }
      case 'Escape':
        if (open !== group.id) return;
        event.preventDefault();
        close();
        // Focus goes back to the label, so the menu never swallows the caret.
        toggleRefs.current[group.id]?.focus();
        return;
      case 'Tab':
        close();
        return;
      default:
    }
  };

  /* A click anywhere outside the header closes whatever is open. */
  useEffect(() => {
    if (open === null) return;
    const onPointerDown = (event: MouseEvent) => {
      if (barRef.current && !barRef.current.contains(event.target as Node)) {
        close();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open, close]);

  /* A route change closes it too: a dropdown left open over the new page would be
     the one thing grouping is likely to get wrong. */
  useEffect(() => {
    close();
  }, [pathname, close]);

  return (
    <nav className="navbar" ref={barRef}>
      <div className="container">
        <Link href="/" className="brand">
          SportDay
        </Link>
        <div className="nav-right">
          {user ? (
            <>
              {/* The everyday links stay out of the menus. Browsing the programme
                  and an athlete's own entries are what most readers come for, and
                  burying them would be the one regression grouping could cause —
                  `/events` is the programme, visible to every role. */}
              <Link href="/events">{t('nav.events')}</Link>
              <Link href="/my-enrollments">{t('nav.myEntries')}</Link>
              {/* A teacher's own page, shown to ADMIN and TEACHER exactly as the
                  flat menu showed it. Kept beside the menus rather than inside
                  one: it is the teacher's day-to-day task, not an administrative
                  errand, and putting it one level down would slow them down. */}
              {canHelpStudents && <Link href="/teacher">{t('nav.helpStudents')}</Link>}

              {visible.map(group => {
                const isOpen = open === group.id;
                return (
                  <div
                    key={group.id}
                    className="nav-dropdown"
                    onKeyDown={event => onMenuKeyDown(event, group)}
                  >
                    <button
                      type="button"
                      className="nav-dropdown-toggle"
                      ref={element => {
                        toggleRefs.current[group.id] = element;
                      }}
                      /* The menu is a menu: the label says so, and says whether it
                         is open. Both are read out by a screen reader. */
                      aria-haspopup="menu"
                      aria-expanded={isOpen}
                      /* A click keeps whatever focus was on, so the arrow keys a
                         keyboard user is already using still work. */
                      onMouseDown={event => event.preventDefault()}
                      onClick={() => {
                        if (isOpen) {
                          close();
                        } else {
                          setOpen(group.id);
                          itemRefs.current = [];
                        }
                      }}
                    >
                      {t(group.key)}
                    </button>
                    {isOpen && (
                      <div className="nav-dropdown-menu" role="menu" aria-label={t(group.key)}>
                        {group.items.map((item, index) => (
                          <Link
                            key={item.href}
                            href={item.href}
                            role="menuitem"
                            ref={element => {
                              itemRefs.current[index] = element;
                            }}
                            /* Keyboard users are often on a slow link and the
                               menu is the moment they have committed; a mouse
                               user's pointer already warmed the page on hover. */
                            onFocus={() => router.prefetch(item.href)}
                            onClick={() => close()}
                          >
                            {t(item.key)}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                );
              })}

              <span style={{ marginLeft: '1rem', color: '#aaa' }}>
                {user.fullName || user.username}
                {user.role && (
                  <span className="badge badge-info" style={{ marginLeft: '0.5rem' }}>
                    {label('role', user.role)}
                  </span>
                )}
              </span>
              {/* Not navigation: the switch and Logout stay where they were. */}
              <LanguageSwitch />
              <button
                onClick={handleLogout}
                className="btn btn-sm btn-danger"
                style={{ marginLeft: '0.75rem' }}
              >
                {t('nav.logout')}
              </button>
            </>
          ) : (
            <>
              <Link href="/login">{t('nav.login')}</Link>
              <LanguageSwitch />
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
