'use client';

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';

/**
 * Bilingual UI (English / Traditional Chinese).
 *
 * Usage in a client component:
 *
 *   const { t, lang, setLang } = useI18n();
 *   <h1>{t('nav.events')}</h1>
 *
 * Adding a string: put the key in BOTH the `en` and `zh` blocks of `messages`
 * below — the `MessageKey` type is derived from `en`, so a key that exists only
 * in `zh` is dead code and a key used in a component but missing from `en` is a
 * TypeScript error. Use `{name}` placeholders with the second argument:
 * `t('grid.marked', { marked: 3, total: 8 })`.
 */

export type Lang = 'en' | 'zh';

export const LANGUAGES: ReadonlyArray<{ code: Lang; label: string; short: string }> = [
  { code: 'en', label: 'English', short: 'EN' },
  { code: 'zh', label: '中文', short: 'CH' },
];

const STORAGE_KEY = 'sportday.lang';

const en = {
  /* ---------------- navigation ---------------- */
  'nav.events': 'Events',
  'nav.myEntries': 'My Entries',
  'nav.results': 'Results',
  'nav.admin': 'Admin',
  'nav.marks': 'Mark Entry',
  'nav.print': 'Print Sheets',
  'nav.records': 'Records',
  'nav.championships': 'Championships',
  'nav.settings': 'Settings',
  'nav.users': 'Users',
  'nav.sportDay': 'Sport day',
  'nav.login': 'Login',
  'nav.logout': 'Logout',
  'nav.language': 'Language',
  'nav.teachers': 'Teacher accounts',
  'nav.helpStudents': 'Help a student',
  /* The two relay families, each on its own page. */
  'nav.relayFormEvents': 'Form class relays',
  'nav.relayHouseEvents': 'Grade house relays',

  /* ---------------- common ---------------- */
  'common.loading': 'Loading…',
  'common.saving': 'Saving…',
  'common.cancel': 'Cancel',
  'common.close': 'Close',
  'common.refresh': 'Refresh',
  'common.reset': 'Reset',
  'common.print': 'Print',
  'common.search': 'Search',
  'common.all': 'All',
  'common.none': 'None',
  'common.actions': 'Actions',
  'common.retry': 'Try again',

  /* ---------------- roles ---------------- */
  'role.ADMIN': 'Administrator',
  'role.MANAGER': 'Manager',
  'role.STUDENT': 'Student',
  'role.USER': 'Staff',
  'role.TEACHER': 'Teacher',
  'role.HELPER': 'Input helper',

  /*
   * The final waits for the heat results. `finalState` is the server's own
   * verdict on an event's final, and these are the words a screen says when it
   * cannot offer the stage: `NOT_DRAWN` points at the heat results, while
   * `finalNoStageHint` covers the two states that have no final at all.
   */
  'final.state.NONE': 'No final stage',
  'final.state.DIRECT': 'Direct to final',
  'final.state.NOT_DRAWN': 'Final not drawn',
  'final.state.DRAWN': 'Final drawn',
  'final.notDrawnHint':
    'The heat results come first. Record the heat marks, then draw the final from them.',
  'final.noStageHint':
    'This event has no final stage, so heats are the only sheet to work on.',

  /* ---------------- domain vocabulary ---------------- */
  'category.TRACK': 'Track 徑項',
  'category.FIELD': 'Field 田項',
  /*
   * A relay is its own family, not a track event: it is run and scored by team.
   * 接力 is the wording the relay screens already use (接力項目), so the label
   * reads as the rest of the app does.
   */
  'category.RELAY': 'Relay 接力',
  'sex.MALE': 'Boys 男',
  'sex.FEMALE': 'Girls 女',
  'grade.A': 'A Grade (17+)',
  'grade.B': 'B Grade (15-16)',
  'grade.C': 'Grade C (14 or below)',
  'grade.short.A': 'A',
  'grade.short.B': 'B',
  'grade.short.C': 'C',
  'sheet.A5': 'A5',
  'sheet.A4': 'A4',
  /*
   * The units an athletics programme prints: `M` for the field events and `s`
   * for the track. Both are short enough for a table cell.
   */
  'unit.M': 'M',
  'unit.s': 's',
  /*
   * The words the API used to store. Kept as the rendering fallback for results
   * and school records saved before the switch, which still carry them.
   */
  'unit.seconds': 'seconds',
  'unit.metres': 'metres',

  /* ---------------- auth ---------------- */
  'auth.signIn': 'Sign in',
  'auth.signingIn': 'Signing in…',
  'auth.username': 'Username',
  'auth.password': 'Password',
  'auth.studentId': 'Student ID',
  'auth.studentHint':
    'Students sign in with their student ID. The password is the date of birth as yyyyMMdd, followed by the class and class number — for example 201003155A12 for a student born 2010-03-15 in class 5A, number 12.',
  'auth.staffHint': 'Staff sign in with their admin or manager account.',
  'auth.loginFailed': 'Could not sign in',

  /* ---------------- events ---------------- */
  'events.title': 'Events',
  'events.quota': 'Quota',
  'events.enter': 'Enter',
  'events.withdraw': 'Withdraw',
  'events.entered': 'Entered',
  'events.entries': 'Entries',
  'events.groupSize': 'Group size',
  'events.sheet': 'Sheet',
  'events.date': 'Date',

  /* ---------------- my entries ---------------- */
  'my.title': 'My Entries',
  'my.heat': 'Heat',
  'my.lane': 'Lane',
  'my.notAllocated': 'Not allocated yet',
  'my.noEntries': 'You have not entered any events yet.',

  /* ---------------- shared event filters (sex / grade / category / event) ---------------- */
  'eventFilters.sex': 'Division',
  'eventFilters.allSexes': 'All divisions',
  'eventFilters.grade': 'Grade',
  'eventFilters.allGrades': 'All grades',
  'eventFilters.category': 'Category',
  'eventFilters.allCategories': 'All categories',
  'eventFilters.eventType': 'Event',
  'eventFilters.allEventTypes': 'All events',

  /* ---------------- marking sheets / print ---------------- */
  'print.title': 'Print marking sheets',
  'print.subtitle':
    'One sheet per heat for the helper to write the results on. Short sprints print on A5, everything else on A4.',
  'print.division': 'Division',
  'print.category': 'Category',
  'print.event': 'Event',
  'print.allEvents': 'All events with heats',
  'print.preview': 'Preview',
  'print.downloadAll': 'Download all matching sheets',
  'print.gradeDownloadHint':
    'The whole-run download covers every grade. Clear the grade filter to use it, or download each event below.',
  'print.downloadEvent': 'Download this event',
  'print.heats': 'Heats',
  'print.athletes': 'Athletes',
  'print.noHeats': 'No heats have been allocated yet.',
  'print.needHeats': 'Allocate heats first, then come back to print.',
  /* An event whose final is still to come is held back whole: printing only the
     heats would look like the final's sheet had gone missing. */
  'print.finalNotDrawn': 'This event runs a final, but the final has not been drawn yet.',
  'print.finalNotDrawnHint':
    'The heat results come first. Record the heat marks and draw the final, then its sheet can be printed.',
  'print.allHeldBack':
    'The whole run is held back while any event it covers is still waiting for its final: printing only the heats would look like the final’s sheet had gone missing.',
  'print.browserPrint': 'Print in browser',
  'print.columns':
    'Each sheet has five columns: student ID, name, grade, record and remark. Record and remark are left blank for the marker.',
  'print.allDivisions': 'All divisions',
  'print.allCategories': 'All categories',
  'print.matchingCount': '{count} matching event(s), {heats} heat(s) in total',
  'print.noMatching': 'No events match the current filters.',
  /*
   * A relay that is not ready to be marked is left out of the list whole, because
   * its sheets are refused — and it says so, in the reason the server sent, rather
   * than simply going missing.
   */
  'print.relaysNotReady':
    '{count} relay(s) are left out because they are not ready to be marked yet:',
  'print.previewTitle': 'Preview — {name}',
  'print.downloadHeat': 'Download {label}',
  'print.previewFailed': 'Failed to open the preview',
  'print.finalCount': 'Final: {count}',

  /* ---------------- mark entry grid ---------------- */
  'marks.title': 'Mark entry',
  'marks.subtitle': 'Type the results straight into the grid and save them all at once.',
  'marks.pickEvent': 'Event',
  'marks.pickGroup': 'Heat',
  /* The picker is narrowed by the event's own grade; the sheet's rows still
     carry the athlete's grade, which is what the second filter narrows. */
  'marks.eventGrade': 'Event grade',
  'marks.pickGrade': 'Athlete grade',
  'marks.allGroups': 'All heats',
  'marks.allGrades': 'All grades',
  'marks.studentId': 'Student ID',
  'marks.name': 'Name',
  'marks.grade': 'Grade',
  'marks.class': 'Class',
  /* The form a class belongs to, and the house with its short code, so a row of
     the mark grid reads `Form 5 · 5D 8 · Red (R)` exactly as the register does. */
  'marks.form': 'Form',
  'marks.house': 'House',
  'marks.heat': 'Heat',
  'marks.lane': 'Lane',
  'marks.record': 'Record',
  'marks.standard': 'Standard {standard}',
  'marks.belowStandard': 'Below standard ({standard})',
  'marks.heatRecord': 'Heat',
  'nav.standards': 'Standards',
  'nav.students': 'Students',
  'nav.manageEvents': 'Manage events',
  'nav.groupSetup': 'Set up',
  'nav.groupRun': 'Run the day',
  'nav.groupReview': 'Review',
  'nav.groupAdmin': 'Admin',
  'standards.title': 'Required standards',
  'standards.intro':
    'The qualifying mark an athlete must reach. Only the track races of 400M and over, and the field events, carry one — a sprint under 400M is not qualifying, and neither is a relay. A race meets its standard at or under it; a field event at or over. Leave a box empty for no standard, then save the page once.',
  'standards.empty': 'No event in this programme carries a required standard.',
  'standards.listed': '{count} events carry a required standard.',
  'standards.event': 'Event',
  'standards.standard': 'Standard',
  'standards.unit': 'Unit',
  'standards.none': 'No standard',
  'standards.saveAll': 'Save standards',
  'standards.savedCount': '{count} standards saved.',
  'standards.failedCount': '{count} standards could not be saved:',
  'standards.saveFailed': 'Could not be saved',
  'standards.invalid': '{event}: "{value}" is not a number greater than zero',
  'standards.fixFirst': 'Nothing was saved. Fix these standards first:',
  'standards.nothingToSave': 'No standard has been changed.',
  'standards.blankSkipped': 'Only the boxes you change are saved.',
  'standards.changedCount': '{count} changed',
  'standards.defaultsTitle': 'Default standards — set once per grade and event type',
  'standards.defaultsIntro':
    'Set the qualifying mark once for an event type, a grade and a division — "400M, A Grade, Boys" — and every event of that key inherits it. A new event picks its standard up by itself.',
  'standards.defaultsKey':
    'The key is event type × grade × division. Boys and girls are separate on purpose: they are separate races with their own qualifying marks, so a boys\u2019 default is never applied to a girls\u2019 event.',
  'standards.grade': 'Grade',
  'standards.sex.MALE': 'Boys',
  'standards.sex.FEMALE': 'Girls',
  'standards.inheritors': 'inherited by {count} event(s)',
  'standards.noEvents': 'no such event in this programme',
  'standards.invalidDefault':
    '{event} {grade}: "{value}" is not a number greater than zero',
  'standards.saveDefaults': 'Save default standards',
  'standards.applyTitle': 'Apply the defaults to events that already exist',
  'standards.applyIntro':
    'Saving a default changes what a new event inherits; it changes no event already on the programme. This is the step that updates them, and it is separate so nothing is rewritten by accident. Preview first: it says exactly how many events would change and writes nothing.',
  'standards.applyHandSet':
    'A standard typed on one event is an exception to its grade, and a plain apply never overwrites it — it is counted and reported instead. Only "overwrite every event" replaces one.',
  'standards.preview': 'Preview',
  'standards.applyInherited': 'Apply to events that follow a default',
  'standards.applyAll': 'Overwrite every event, including hand-set',
  'standards.previewCount':
    'Preview: {count} event(s) would change, {kept} hand-set standard(s) left alone. Nothing has been written.',
  'standards.appliedCount': '{count} event(s) updated, {kept} hand-set standard(s) left alone.',
  'standards.keptNote':
    '{count} event(s) hold a standard somebody set by hand. They were not touched.',
  'standards.applyFailed': 'The defaults could not be applied',
  'standards.wouldBecome': 'Would become',
  'standards.exceptionsTitle': 'Exceptions — one event at a time',
  'standards.exceptionsIntro':
    'For a race that does not use its grade\u2019s default, set its own standard here. A number typed here belongs to this event alone and is never replaced by an apply.',
  'standards.showExceptions': 'Show all {count} qualifying events',
  'standards.hideExceptions': 'Hide the per-event boxes',
  'standards.source': 'Where it came from',
  'standards.fromDefault': 'Grade default',
  'standards.handSet': 'Set by hand',
  'marks.remark': 'Remark',
  'marks.unit': 'Unit',
  /*
   * A race timed on a stopwatch — the 400M and over, and both relays — is
   * written the way the school writes it: minutes, seconds, milliseconds. One
   * box, one shape, and the column heading says which.
   */
  'marks.timeFormat': 'M.SS.mmm',
  'marks.timePlaceholder': '1.04.123',
  'marks.timeBadShape':
    '{who}: "{value}" is not a time — write M.SS.mmm, e.g. 1.04.123, 0.48.123 or 48.123.',
  'marks.timeSecondsLimit':
    '{who}: the seconds part of a time must be under 60 — write 2 minutes 15 seconds as 2.15.000, not as 1.75.000.',
  'marks.attempt': 'Attempt {n}',
  'marks.best': 'Best',
  'marks.fieldHint':
    'Field event — three attempts each. Type them in order, leave a miss blank, and the best one counts.',
  'marks.marked': '{marked} of {total} recorded',
  'marks.saveAll': 'Save all marks',
  'marks.clearMark': 'Clear',
  'marks.saveSummary': '{saved} saved, {cleared} cleared, {skipped} unchanged, {failed} failed',
  'marks.leaderboard': 'Leaderboard',
  'marks.rank': 'Rank',
  'marks.pickEventFirst': 'Choose an event to start entering marks.',
  /*
   * Only events with more than one athlete entered are worth marking, so the
   * picker leaves the rest out — and says so rather than showing a blank list.
   */
  'marks.thinEventsHidden':
    '{count} event(s) are left out: a mark is only worth taking with more than one athlete entered.',
  'marks.noMarkableEvents':
    'No event has more than one athlete entered yet, so there is nothing to mark.',
  /*
   * A relay that is not ready to be marked is left out of the picker whole: it has
   * too few teams, or a team short of its runners, and the server refuses to mark it
   * with a 409. It says so — in the reason the server sent — rather than simply
   * going missing, which would read as a broken page.
   */
  'marks.relaysNotReady':
    '{count} relay(s) are left out because they are not ready to be marked yet:',
  'marks.blankSkipped': 'Rows left blank are not changed.',
  'marks.unsaved': 'Unsaved changes',
  'marks.invalidMark': '{who}: "{value}" is not a number',
  'marks.remarkNeedsRecord': '{who}: a remark needs a record',
  'marks.fixBeforeSaving': 'Nothing was saved. Fix these rows first:',
  'marks.loadFailed': 'Failed to load the mark sheet',
  'marks.loadingSheet': 'Loading the grid…',
  'marks.saveFailed': 'Failed to save the marks',
  'marks.nothingToSave': 'There is nothing to save yet.',
  'marks.saveErrors': 'Rows that could not be saved',
  'marks.noRows': 'No athletes match the current filters.',
  'marks.filterHint': 'Changing the heat or grade reloads the grid from the server.',
  'marks.groupOption': '{label} · {count} athletes',
  'marks.pickStage': 'Stage',
  'marks.stageHeat': 'Heats 初賽',
  'marks.stageFinal': 'Final 決賽',
  /* The final cannot be worked on until it has been drawn, and an event that
     has no final stage is not offered one at all. Both are said, not greyed. */
  'marks.stageUnavailable': 'Not available yet',
  'marks.finalHint': 'The final is a single race, so the heat filter does not apply.',
  'marks.finalNotDrawn': 'The final has not been drawn yet.',
  'marks.finalNotDrawnHint':
    'Draw the top 8 from the heats on the event’s groups page first, then come back to record the final marks.',
  'marks.openGroups': 'Go to heats & final',
  'marks.finalQualifiers': '{count} qualifiers in the final',
  'marks.finalSheetHint': 'Final sheet — print on {sheet}',
  /*
   * A helper may record that an athlete was absent (ABS) or disqualified (DQ)
   * instead of a mark. The choice sits beside the number box: the first option
   * leaves the row a number, the other two record the outcome with no mark.
   */
  'marks.outcome': 'Outcome',
  'marks.outcomeResult': 'Result',
  'marks.outcomeAbs': 'ABS',
  'marks.outcomeDq': 'DQ',
  'marks.outcomeHint':
    'ABS records an athlete who did not compete and DQ one who was disqualified. Either clears the number and the attempts for that row, and neither is checked against the mark rules.',

  /* ---------------- admin ---------------- */
  'admin.title': 'Administration',
  'admin.students': 'Student register',
  'admin.studentsHint': 'Upload the register, check the grades, hand out passwords.',
  'admin.events': 'Event catalogue',
  'admin.eventsHint': 'Create the standard events and enable or disable them.',
  'admin.seasonReset': 'Reset the season',
  'admin.seasonResetConfirm': 'Delete every entry, heat and recorded result? Students and events are kept.',
  'admin.results': 'Record a single result',
  'admin.teachers': 'Teacher accounts',
  'admin.teachersHint': 'Upload the staff list and see which classes each teacher may help in.',

  /* ---------------- admin: students ---------------- */
  'students.title': 'Student register',
  'students.referenceDate': 'Grade reference date',
  'students.generateSample': 'Generate 600 sample students',
  'students.recomputeGrades': 'Recompute grades',
  'students.total': 'Students',
  'students.byGrade': 'By grade',
  'students.credentials': 'Credentials CSV',
  'students.template': 'Upload template',
  'students.sampleCsv': 'Sample register CSV',
  'students.colId': 'Student ID',
  'students.colName': 'Name',
  'students.colDob': 'Date of birth',
  'students.colAge': 'Age',
  'students.colHouse': 'House',
  /* The form a class belongs to — `5D` is Form 5 — and the house with its short
     code. `formValue` is the one wording of "Form 5" used wherever a student is
     listed, so the register, the relay board and the mark grid cannot disagree. */
  'students.form': 'Form',
  'students.formValue': 'Form {form}',
  'students.houseCodeHint':
    'A house’s short code — R, Y, B, G. A house the school has not given a code is shown by its name alone.',
  'students.importResult': 'Import result',
  'students.created': 'Created',
  'students.updated': 'Updated',
  'students.failed': 'Failed',
  'students.rowErrors': 'Rows that could not be imported',
  'students.row': 'Row',
  'students.entries': 'Entries',

  /* ---------------- admin: events ---------------- */
  'adminEvents.title': 'Event catalogue',
  'adminEvents.createDefaults': 'Create the standard catalogue',
  'adminEvents.new': 'New event',
  'adminEvents.enabled': 'Enabled',
  'adminEvents.disabled': 'Disabled',
  'adminEvents.enable': 'Enable',
  'adminEvents.disable': 'Disable',
  'adminEvents.delete': 'Delete',
  'adminEvents.deleteConfirm': 'Delete this event, its entries and its results?',
  'adminEvents.directToFinalWarning':
    'This event already has heats or a final drawn. Changing the format now affects a programme that may already have been run.',

  /* ---------------- groups / heats ---------------- */
  'groups.title': 'Heats',
  'groups.allocate': 'Allocate heats',
  'groups.clear': 'Remove all heats',
  'groups.clearConfirm': 'Remove every heat from this event?',
  'groups.noGroups': 'No heats yet.',
  'groups.noGroupsRelay':
    'A relay is divided into teams, not heats — its sheet prints one line per team. Build its teams on the relay board.',

  /* ---------------- results ---------------- */
  'results.title': 'Results',
  'results.notes': 'Notes',
  'results.noResults': 'No results recorded yet.',

  /* ---------------- common (extended) ---------------- */
  'common.sex': 'Sex',
  'common.details': 'Details',
  'common.edit': 'Edit',
  'common.processing': 'Working…',
  'common.creating': 'Creating…',
  'common.upload': 'Upload',
  'common.uploading': 'Uploading…',
  'common.downloading': 'Downloading…',
  'common.downloaded': 'Downloaded {filename}',
  'common.saveChanges': 'Save changes',
  'common.backToAdmin': 'Back to admin',
  'common.backToEvents': 'Back to events',

  /* ---------------- home ---------------- */
  'home.welcome': 'Welcome, {name}!',
  'home.subtitle': 'What would you like to do today?',
  'home.eventsDesc': 'Browse and enroll in sporting events',
  'home.resultsDesc': 'View leaderboards and event results',
  'home.myEntriesDesc': 'Manage your event registrations',
  'home.adminTitle': 'Admin panel',
  'home.adminDesc': 'Manage events, users, and results',
  'home.upcoming': 'Upcoming events',

  /* ---------------- auth (extended) ---------------- */
  'auth.loginTitle': 'Login to SportDay',
  'auth.usernamePlaceholder': 'Staff username, or student id e.g. S0003',
  'auth.studentLogin': 'Student login',
  'auth.show': 'Show',
  'auth.hide': 'Hide',
  'auth.adminCredentials': 'Admin: {username} / {password}',
  'auth.email': 'Email',
  'auth.fullName': 'Full name',

  /* ---------------- events (extended) ---------------- */
  'events.loadFailed': 'Failed to load events',
  'events.loadingEvents': 'Loading events…',
  'events.enteredNotice': 'Entered: {type} — {name}',
  'events.enterFailed': 'Could not enter {name}',
  'events.withdrawConfirm': 'Withdraw from {name}?',
  'events.withdrawnNotice': 'Withdrawn: {type} — {name}',
  'events.withdrawFailed': 'Could not withdraw from {name}',
  'events.fullCount': 'Event full ({count}/{max})',
  'events.trackQuotaFull': 'Track quota full ({used}/{max})',
  'events.fieldQuotaFull': 'Field quota full ({used}/{max})',
  'events.manage': 'Manage events',
  'events.trackUsed': 'Track {used}/{max}',
  'events.fieldUsed': 'Field {used}/{max}',
  'events.remaining': 'Remaining — track {track} · field {field}',
  'events.sexDivision': 'Sex division',
  'events.noEventsDivision': 'No events in this division.',
  'events.place': 'Location',
  'events.participants': 'Participants',
  'events.ungroupedCount': '({count} ungrouped)',
  'events.notFound': 'Event not found',
  'events.name': 'Event name',
  'events.description': 'Description',
  'events.type': 'Type',
  'events.maxParticipants': 'Max participants',
  'events.sheetSize': 'Sheet size',
  'events.maxEntries': 'Max entries per student',
  'events.shortSprint': 'Short sprint (lane-based)',
  'events.lanesPhotoFinish': 'Lanes & photo-finish',
  'events.directToFinal': 'Direct to final',
  'events.directToFinalHint':
    'The event is decided by its own run. Untick this to run heats and then a final.',
  'events.directToFinalForced': 'Only 60M, 100M, 200M and 400M can be run as heats and a final.',
  /*
   * The system switches a short sprint with eight or fewer entries to direct to
   * a final by itself. Say so plainly, so it is never mistaken for the school's
   * own choice of format — `EventDTO.directToFinalAutomatic`.
   */
  'events.directToFinalAutomatic': 'set automatically',
  'events.directToFinalAutomaticHint':
    'This event has 8 or fewer athletes entered, so a final would be the same runners as the heat. It will go back to heats and a final if more enter.',
  'events.heatsAndFinal': 'Heats + final',
  'events.format': 'Format',
  'events.visibleToStudents': 'Visible to students',
  'events.event': 'Event',
  'events.backToEvent': 'Back to event',
  'events.browse': 'Browse events',

  /* ---------------- grade, seen from the entry list ---------------- */
  'events.gradeNotAllowed': 'This is the {grade} grade event and you are in the {mine} grade.',
  'events.gradeSummary': '{allowed} of {total} events are for the {grade} grade, and only those are shown',
  'events.gradeUnknown':
    'Your grade is not on your profile, so every event is shown. The server still refuses an event of another grade.',

  /* ---------------- the programme by date ---------------- */
  'events.programmeDate': 'Programme date',
  'events.allDates': 'All dates',
  'events.dateOption': '{date} · {count} events',
  'events.dateOptionToday': '{date} · {count} events · Today',
  'events.dateOptionPast': '{date} · {count} events · Past',
  'events.dateSummary': '{count} events on {date}',
  'events.allDatesSummary': 'All {count} events',

  /* ---------------- the programme by school year ---------------- */
  'events.schoolYear': 'School year',
  'events.allYears': 'All school years',
  'events.yearOption': '{year} · {count} events',
  'events.yearOptionCurrent': '{year} · {count} events · Current',
  'events.yearOptionClosed': '{year} · {count} events · Entries closed',
  'events.viewingYear': 'Viewing {year} — {name}',
  'events.viewingAllYears': 'Showing every school year',
  'events.entriesClosedYear':
    'Entries are closed for {year}. You can still browse the programme.',

  /* ---------------- my entries (extended) ---------------- */
  'my.loadFailed': 'Failed to load your entries',
  'my.withdrawnNotice': 'Withdrawn: {name}',
  'my.withdrawFailed': 'Could not withdraw',
  'my.reEnteredNotice': 'Re-entered: {name}',
  'my.reEnterFailed': 'Could not re-enter',
  'my.loadingEntries': 'Loading your entries…',
  'my.reEnter': 'Re-enter',
  'my.confirmed': 'Confirmed',
  'my.withdrawn': 'Withdrawn',
  'my.entryGradeNotAllowed':
    'Your grade no longer matches this event. The entry stands — a new entry would be refused.',

  /* ---------------- results (extended) ---------------- */
  'results.athlete': 'Athlete',
  'results.eventResults': 'Event results',
  'results.selectEvent': 'Select event',
  'results.chooseEvent': 'Choose an event…',
  'results.noResultsEvent': 'No results recorded for this event yet.',
  'results.recordResult': 'Record result',
  'results.recordTitle': 'Record event result',
  'results.recorded': 'Result recorded.',
  'results.recordFailed': 'Failed to record the result',
  'results.selectAthlete': 'Select athlete',
  'results.markPlaceholder': 'e.g. 10.123',
  'results.unitPlaceholder': 'e.g. s, M',
  'results.notesPlaceholder': 'Optional notes…',
  'results.tabEvent': 'By event',
  'results.tabPast': 'Past events',
  'results.pastHint':
    'Events dated today or earlier, most recent first. Pick one to see how it finished.',
  'results.pastHintYear':
    'Events of the chosen school year, dated today or earlier. Pick one to see how it finished.',
  'results.noPastEventsYear': 'No past events in this school year.',
  'results.yearFilter': 'School year',
  'results.selectPastEvent': 'Past event',
  'results.choosePastEvent': 'Choose a past event…',
  'results.noPastEvents': 'No past events yet.',
  'results.pastStandings': 'Placings',
  'results.pastNoResults': 'No placings recorded for this event yet.',
  'results.newRecord': 'New record',
  'results.place': 'Place',
  /*
   * The result column: the mark with the unit the sport writes it in, as one
   * value (`14.123s`, `2.15.5s`, `18.12M`) rather than a mark beside a unit.
   */
  'results.result': 'Result',
  'results.downloadEventPdf': 'Download this event’s results',
  'results.downloadAllPdf': 'Download all results',
  'results.pdfNothingForEvent':
    'No results have been recorded for this event yet, so there is nothing to print.',
  'results.pdfNothingForProgramme':
    'No results have been recorded yet, so there is nothing to print.',
  'results.pdfFailed': 'Failed to download the results PDF',
  /*
   * The results of one event are picked through a cascade — sex, grade, event,
   * then the stage — because an event belongs to a single grade and only a short
   * sprint that ran a final has a second stage to choose.
   */
  'results.stage': 'Stage',
  'results.choose': 'Choose…',
  'results.pickEventFirst': 'Choose a sex, a grade and an event to see its results.',
  'results.noStageResults': 'No {stage} results for this event yet.',
  'results.noFinal': 'This event was decided by its heats — no final was run.',

  /* ---------------- school records ---------------- */
  'records.title': 'School records',
  'records.subtitle': 'The best mark of each event in every division and grade.',
  'records.groupBy': 'Group by',
  'records.byCategory': 'Category',
  'records.byEvent': 'Event',
  'records.rebuild': 'Rebuild records',
  'records.rebuilding': 'Rebuilding…',
  'records.rebuildConfirm':
    'Rebuild every school record from the recorded marks? This replaces the current records.',
  'records.rebuilt': 'Records rebuilt — {count} records from {rebuilt} marks.',
  'records.rebuildFailed': 'Failed to rebuild the records',
  'records.loadFailed': 'Failed to load the school records',
  'records.count': '{count} records',
  'records.empty': 'No school records have been set yet.',
  'records.emptyHint': 'Record marks in the mark-entry grid, then rebuild the records.',
  'records.colEvent': 'Event',
  'records.colDivision': 'Division',
  'records.colGrade': 'Grade',
  'records.colMark': 'Best mark',
  'records.colHolder': 'Holder',
  'records.colAchieved': 'Achieved on',
  'records.colPrevious': 'What it beat',
  'records.previousLine': '{name} — {mark}{unit} on {date}',
  'records.firstRecord': 'First record',
  'records.colSource': 'Source',
  'records.sourceBaseline': 'Typed in by hand',
  'records.sourceResult': 'Held by a result',
  'records.sourceNone': 'Not set yet',
  'records.colTypedIn': 'Typed in by hand',
  'records.noMark': 'No mark yet',
  'records.noTypedIn': 'Nothing typed in',
  'records.baselineNote':
    'A mark you type in stays as a fallback: it holds the record until a result beats it, and survives every rebuild.',
  'records.baselineBeaten': 'You typed {mark} {unit}; a result has since beaten it.',
  'records.baselineHeld': 'The typed-in mark is what stands.',
  'records.resultHeld': 'A recorded result holds this record.',
  'records.editBaseline': 'Edit the typed-in mark',
  'records.fieldMark': 'Mark',
  'records.fieldUnit': 'Unit',
  'records.holderPlaceholder': 'e.g. Chan Tai Man (2018)',
  'records.saveBaseline': 'Save the typed-in mark',
  'records.baselineSaved': 'Typed-in mark saved.',
  'records.baselineSaveFailed': 'Failed to save the typed-in mark',
  'records.baselineMarkRequired': 'Enter a mark before saving.',
  'records.clearBaseline': 'Clear the typed-in mark',
  'records.clearBaselineConfirm':
    'Clear the typed-in mark for {event} ({division} · {grade})? The record falls back to the results.',
  'records.baselineCleared': 'Typed-in mark cleared — the record follows the results again.',
  'records.baselineClearFailed': 'Failed to clear the typed-in mark',
  'records.seed': 'Create missing records',
  'records.seeding': 'Creating…',
  'records.seeded': 'Created {created} missing record(s) — {count} in total.',
  'records.seedNone': 'Every record row already exists — {count} in total.',
  'records.seedFailed': 'Failed to create the missing records',
  'records.rebuildKeepsBaseline': 'Rebuilding keeps every hand-entered mark.',

  /* ---------------- championships ---------------- */
  'championships.title': 'Championships',
  'championships.subtitle':
    'Personal and house points, worked out from every scored event on or before the reference date.',
  'championships.referenceDate': 'Reference date',
  'championships.eventsScored': 'Events scored',
  'championships.athletesScored': 'Athletes scored',
  'championships.scoringStage': 'Scoring stage',
  'championships.pointsScale': 'Points scale',
  'championships.loadFailed': 'Failed to load the championships',
  'championships.personalTitle': 'Personal championship',
  'championships.personalEmpty': 'No athlete has scored points yet.',
  'championships.housesTitle': 'House championship',
  'championships.housesEmpty': 'No house has scored points yet.',
  'championships.eventsTitle': 'Event placings',
  'championships.eventsEmpty': 'No event has been scored yet.',
  'championships.colRank': 'Rank',
  'championships.colAthlete': 'Athlete',
  'championships.colGrade': 'Grade',
  'championships.colClass': 'Class',
  'championships.colHouse': 'House',
  'championships.colPoints': 'Points',
  'championships.colGold': 'Gold',
  'championships.colSilver': 'Silver',
  'championships.colBronze': 'Bronze',
  'championships.colEventsScored': 'Events',
  'championships.colAthletes': 'Athletes',
  'championships.leader': 'Leader',
  'championships.stageFinal': 'Final',
  'championships.stageHeat': 'Heats',
  'championships.stageHint':
    'An event is scored on its final where a final was run, and on the heats otherwise.',
  'championships.relayHint': 'A relay placing is marked RELAY and scores for the house only.',
  'championships.relay': 'Relay',
  'championships.relayTag': 'RELAY',
  'championships.schoolRecord': 'School record',
  'championships.noPlacings': 'No placings recorded.',
  'championships.toggleEvent': 'Show the placings',

  /* ---------------- admin: settings ---------------- */
  'settings.title': 'Settings',
  'settings.subtitle': 'Entry limits and the points scale used by the championships.',
  'settings.loadFailed': 'Failed to load the settings',
  'settings.entryLimits': 'Entry limits',
  'settings.entryLimitsHint': 'How many events one student may enter.',
  'settings.trackMaxEntries': 'Track events per student',
  'settings.fieldMaxEntries': 'Field events per student',
  'settings.minOne': 'The entry limits must be at least 1.',
  'settings.points': 'Points',
  'settings.pointsHint':
    'Individual placings score from 1st down to the lowest scoring place; the same three figures apply to relays.',
  'settings.individual': 'Individual',
  'settings.relay': 'Relay',
  'settings.pointsFirst': '1st',
  'settings.pointsSecond': '2nd',
  'settings.pointsThird': '3rd',
  'settings.pointsTopPlace': 'Lowest scoring place',
  'settings.pointsTop': 'Points for 4th to that place',
  'settings.currentScale': 'Current scale',
  'settings.save': 'Save settings',
  'settings.saved': 'Settings saved.',
  'settings.saveFailed': 'Failed to save the settings',
  'settings.reset': 'Restore the defaults',
  'settings.resetConfirm': 'Restore every setting to its documented default?',
  'settings.resetDone': 'Settings restored to the defaults.',
  'settings.resetFailed': 'Failed to restore the defaults',
  'settings.resetting': 'Restoring…',
  'settings.updatedAt': 'Last updated {date}',
  'settings.scale': '1st {first}, 2nd {second}, 3rd {third}, 4th–{topPlace}th {top} · relay {rfirst} / {rsecond} / {rthird}',
  'settings.scaleTopPlace': '4th–{topPlace}th {top} · relay tops {rtop}',
  'settings.unexpectedError': 'Unexpected response from the server',

  /* ---------------- admin: users ---------------- */
  'users.title': 'Users & roles',
  'users.subtitle': 'Staff accounts, roles and access. Passwords are never shown again.',
  'users.loadFailed': 'Failed to load the accounts',
  'users.studentsNotice':
    'Students are not created here — they come from the register import.',
  'users.studentsLink': 'Go to the student register',
  'users.createTitle': 'Create an account',
  'users.createHint':
    'Only ADMIN, MANAGER and USER can be handed out. STUDENT accounts are made by the register import.',
  'users.roleLabel': 'Role',
  'users.create': 'Create account',
  'users.creating': 'Creating…',
  'users.created': 'Account {username} created.',
  'users.createFailed': 'Failed to create the account',
  'users.password': 'Password',
  'users.passwordPlaceholder': 'At least 6 characters',
  'users.roleFilter': 'Role',
  'users.statusFilter': 'Status',
  'users.allRoles': 'All roles',
  'users.allStatuses': 'All statuses',
  'users.searchPlaceholder': 'Username or name',
  'users.enable': 'Enable',
  'users.disable': 'Disable',
  'users.enabledNotice': '{username} enabled',
  'users.disabledNotice': '{username} disabled',
  'users.statusFailed': 'Failed to change the account status',
  'users.delete': 'Delete',
  'users.deleteConfirm':
    'Delete {username}? Their entries and results are removed with the account.',
  'users.deletedNotice': 'Deleted {username}',
  'users.deleteFailed': 'Failed to delete the account',
  'users.count': '{count} account(s)',
  'users.noMatch': 'No accounts match the current filters.',
  'users.validationFull': 'Enter a username, a password of at least 6 characters and an email.',
  'users.email': 'Email',
  'users.fullName': 'Full name',
  'users.passwordHint': 'Shown once and never stored in the browser.',

  /* ---------------- admin (extended) ---------------- */
  'admin.seasonResetHint':
    'Clears entries, heats and results only — events and the student register are kept.',
  'admin.seasonResetDone': 'Season reset — entries, heats and results cleared.',
  'admin.seasonResetFailed': 'Season reset failed',
  'admin.resetting': 'Resetting…',
  'admin.createEventHint': 'Add a new sport event',
  'admin.recordHint': 'Input event marks',
  'admin.leaderboardHint': 'View event results',
  'admin.allUsers': 'All users',
  'admin.colId': 'ID',
  'admin.colRole': 'Role',
  'admin.colStatus': 'Status',
  'admin.active': 'Active',

  /* ---------------- admin: sport day & school years ---------------- */
  'sportDay.title': 'Sport day & school years',
  'sportDay.subtitle':
    'The school’s own details, and one sport day for every school year.',
  'sportDay.loading': 'Loading the sport day…',
  'sportDay.loadFailed': 'Failed to load the school years',
  'sportDay.currentSummary': 'Current year: {year} · entries open',
  'sportDay.currentSummaryClosed': 'Current year: {year} · entries closed',
  'sportDay.noCurrent': 'No year is current, so students cannot enter anything yet.',
  'sportDay.yearsCount': '{count} school year(s)',

  /* --- A. the school's details --- */
  'sportDay.schoolDetails': 'The school’s details',
  'sportDay.schoolDetailsHint':
    'These head every printed marking sheet, so keep them right before the sport day.',
  'sportDay.schoolName': 'School name (English)',
  'sportDay.schoolNameZh': 'School name (Chinese)',
  'sportDay.address': 'Address',
  'sportDay.principal': 'Principal',
  'sportDay.sportDayTitle': 'Sport day title (printed on the sheets)',
  'sportDay.sheetHeading': 'The heading on every marking sheet',
  'sportDay.sheetHeadingHint':
    'Every marking sheet you print is headed with the school’s details and the sport day title.',
  'sportDay.schoolSaved': 'School details saved.',
  'sportDay.schoolFieldsHint':
    'The entry limits and the points scale stay on the settings page.',

  /* --- B. the school years --- */
  'sportDay.years': 'School years',
  'sportDay.yearsHint':
    'One sport day per school year. The current year is the one students may enter.',
  'sportDay.noYears': 'No school year has been created yet.',
  'sportDay.year': 'Year',
  'sportDay.name': 'Name',
  'sportDay.date': 'Sport day date',
  'sportDay.eventCount': 'Event(s)',
  'sportDay.entries': 'Entries',
  'sportDay.entriesOpen': 'Entries open',
  'sportDay.entriesClosed': 'Entries closed',
  'sportDay.current': 'Current',
  'sportDay.notCurrent': 'Not current',
  'sportDay.enrollmentOpen': 'Students may enter this year',
  'sportDay.enrollmentOpenHint':
    'This switch is what lets students enter. With it off, every new entry for this year is refused.',
  'sportDay.openEntries': 'Open entries',
  'sportDay.closeEntries': 'Close entries',
  'sportDay.enrollmentOpened': 'Entries opened for {year}.',
  'sportDay.enrollmentClosed': 'Entries closed for {year}.',
  'sportDay.enrollmentFailed': 'Failed to change the enrolment state',
  'sportDay.viewProgramme': 'View the programme',
  'sportDay.activate': 'Make this the current year',
  'sportDay.activating': 'Activating…',
  'sportDay.activateHint':
    'Makes this the year students may enter, and closes every other year.',
  'sportDay.activateConfirm':
    'Make {year} the year students may enter? Every other year is closed to new entries.',
  'sportDay.activated': '{year} is now the current year — the others are closed.',
  'sportDay.activateFailed': 'Failed to make that the current year',
  'sportDay.create': 'Add a school year',
  'sportDay.createHint':
    'A new year starts with an empty programme unless you copy one into it.',
  'sportDay.createYear': 'Year',
  'sportDay.createName': 'Name',
  'sportDay.createNamePlaceholder': 'e.g. 2027 Sports Day',
  'sportDay.defaultName': '{year} Sports Day',
  'sportDay.createDate': 'Sport day date',
  'sportDay.createOpen': 'Open entries straight away',
  'sportDay.createOpenHint':
    'Off by default: the year is created with entries closed, so nothing is entered by accident.',
  'sportDay.copyFrom': 'Programme',
  'sportDay.copyFromNone': 'Start with an empty programme',
  'sportDay.copyFromYear': 'Copy the programme from {year}',
  'sportDay.createSubmit': 'Create the year',
  'sportDay.creating': 'Creating…',
  'sportDay.created': 'Created {name}.',
  'sportDay.createFailed': 'Failed to create the school year',
  'sportDay.yearRequired': 'Enter the school year as four digits, e.g. 2027.',
  'sportDay.nameRequired': 'Enter a name for the year.',
  'sportDay.dateRequired': 'Enter the sport day date.',
  'sportDay.edit': 'Edit',
  'sportDay.editTitle': 'Edit {year}',
  'sportDay.editHint':
    'A past year can always be corrected — its date, name and notes are kept for the record.',
  'sportDay.notes': 'Notes',
  'sportDay.notesPlaceholder': 'Anything worth remembering about this year…',
  'sportDay.saveYear': 'Save the year',
  'sportDay.saved': 'Saved {name}.',
  'sportDay.saveFailed': 'Failed to save the school year',
  'sportDay.delete': 'Delete',
  'sportDay.deleting': 'Deleting…',
  'sportDay.deleteConfirm': 'Delete {name}? This cannot be undone.',
  'sportDay.deleteConfirmWithEvents':
    'Delete {name}? It still has {count} event(s). Delete or move them first, or the server will refuse.',
  'sportDay.deleted': 'Deleted {name}.',
  'sportDay.deleteFailed': 'Failed to delete the school year',

  /* ---------------- admin: students (extended) ---------------- */
  'students.loadFailed': 'Failed to load students',
  'students.chooseFileFirst': 'Choose a .csv or .xlsx file first.',
  'students.uploadFailed': 'Upload failed',
  'students.uploadFinished':
    'Upload finished — {created} created, {updated} updated, {failed} failed.',
  'students.sampleGenerated':
    'Sample students generated — {created} created, {updated} updated, {failed} failed.',
  'students.sampleFailed': 'Failed to generate sample students',
  'students.recomputeFinished':
    'Recompute finished — {changed} changed, reference date {date} ({counts}).',
  'students.recomputeFailed': 'Failed to recompute grades',
  'students.downloaded': 'Downloaded {label} → {filename}',
  'students.downloadFailed': 'Failed to download {label}',
  'students.gradeRule': 'Grade rule (age on the sport day)',
  'students.gradeRuleLine': 'A grade: {a} · B grade: {b} · C grade: {c}',
  'students.importTools': 'Import & tools',
  'students.registerFile': 'Register file (.csv / .xlsx)',
  'students.generating': 'Generating…',
  'students.computing': 'Recomputing…',
  'students.downloadTokenNote': 'Downloads are fetched with your bearer token.',
  'students.batch': 'Batch',
  'students.file': 'File',
  'students.gradeReference': 'Grade reference',
  'students.message': 'Message',
  'students.searchPlaceholder': 'Student id or name',
  'students.classPlaceholder': 'e.g. 5A',
  'students.loadingStudents': 'Loading students…',
  'students.noMatch': 'No students match the current filters.',

  /* --- the yearly roster upload and the lock preview --- */
  'students.completeList': 'This is the complete student list for this year',
  'students.completeListHint':
    'Tick this and every student missing from the file is locked: they will not be able to sign in, and cannot be entered in an event. If the file is only part of the school, the rest of the school is locked.',
  'students.completeListDone':
    'Complete list imported — {created} created, {updated} updated, {failed} failed.',
  'students.previewLock': 'Preview who would be locked',
  'students.previewing': 'Previewing…',
  'students.lockPreviewTitle': 'Lock preview — nothing has been changed yet',
  'students.lockPreviewHint':
    'This is what would happen. Nobody is locked until you confirm below.',
  'students.wouldLock': 'Students who would be locked',
  'students.lockTotal': '{count} student(s) left out of this file',
  'students.lockSampleNote': 'Showing the first {shown} of {total}.',
  'students.lockConfirm': 'Lock these students now',
  'students.locking': 'Locking…',
  'students.lockDiscard': 'Discard the preview',
  'students.lockDiscarded': 'Nothing was locked.',
  'students.lockApplied':
    'Locked {locked} student(s); {unlocked} returning student(s) restored to active.',
  'students.lockAppliedOnly': 'Locked {locked} student(s).',
  'students.unlockedOnly': '{count} returning student(s) restored to active.',
  'students.rosterUsesServerDate':
    'The complete-list upload works out the grade reference date itself.',
  'students.lockConsequence':
    'A locked student keeps every entry, result and record — they are hidden, not deleted. They cannot sign in or be entered in an event until they are unlocked.',
  'students.lockFailed': 'Failed to lock the students',
  'students.lockMissing': 'Lock everyone left out of the last upload',
  'students.lockMissingHint':
    'For an upload you did not declare complete: locks every student the most recent file left out.',
  'students.lockMissingConfirm':
    'Lock every student the most recent upload left out? They keep their entries and results, but cannot sign in or be entered in an event.',
  'students.lockMissingDone':
    'Locked {locked} student(s) left out of batch {batch} — {now} of them just now.',
  'students.lockMissingFailed': 'Failed to lock the students left out',
  'students.lockingMissing': 'Locking…',

  /* --- locked students on the register --- */
  'students.statusFilter': 'Status',
  'students.statusActive': 'Active',
  'students.locked': 'Locked',
  'students.lock': 'Lock',
  'students.unlock': 'Unlock',
  'students.lockedNotice': '{name} locked',
  'students.unlockedNotice': '{name} unlocked',
  'students.lockStatusFailed': 'Failed to change the lock',
  'students.lockedNote':
    'A locked student keeps their entries, results and records — they are hidden, not deleted.',

  /* ---------------- admin: events (extended) ---------------- */
  'adminEvents.enabledNotice': '{name} enabled',
  'adminEvents.disabledNotice': '{name} disabled',
  'adminEvents.statusFailed': 'Failed to change the event status',
  'adminEvents.deleteConfirmNamed': 'Delete "{name}"? This cannot be undone.',
  'adminEvents.deletedNotice': 'Deleted {name}',
  'adminEvents.deleteFailed': 'Failed to delete the event',
  'adminEvents.pickDate': 'Pick a sport day date first.',
  'adminEvents.defaultsCreated':
    'Default catalogue created — {created} new event(s), {total} total for {date}.',
  'adminEvents.inclField': '(incl. field)',
  'adminEvents.trackOnly': '(track only)',
  'adminEvents.defaultsFailed': 'Failed to create the default catalogue',
  'adminEvents.allEvents': 'All events',
  'adminEvents.ungrouped': 'Ungrouped',
  'adminEvents.defaultCatalogue': 'Default catalogue',
  'adminEvents.defaultCatalogueHint':
    'Creates the standard catalogue (track + field); all new events are enabled by default.',
  'adminEvents.sportDay': 'Sport day',
  'adminEvents.searchPlaceholder': 'Name or type',
  'adminEvents.noMatch': 'No events match the current filters.',
  'adminEvents.heatsCount': '{count} heats',
  'adminEvents.createTitle': 'Create a new event',
  'adminEvents.createFailed': 'Failed to create the event',
  'adminEvents.updateFailed': 'Failed to update the event',
  'adminEvents.editTitle': 'Edit event',
  'adminEvents.toggleEnable': 'Toggle enable',
  'adminEvents.create': 'Create event',
  'adminEvents.year': 'Year',
  'adminEvents.pastYear': 'Past year',
  'adminEvents.defaultsYearNote':
    'New events join the current school year. Switch the year above to work on a past one.',
  'adminEvents.viewingYear': 'Viewing {year} — {name}',
  'adminEvents.viewingAllYears': 'Showing every school year',
  'adminEvents.gradeHint':
    'Every event belongs to exactly one grade, and no grade is ever ranked against another. The standard catalogue does not give every type to every grade: the 5000M is A grade only, and the 1500M and 110M hurdles have no C grade.',
  'adminEvents.gradeNotRun':
    'The {type} is not run by the {grade} grade. Pick a grade this type runs, or change the type.',

  /* ---------------- groups / heats (extended) ---------------- */
  'groups.loadFailed': 'Failed to load heats',
  'groups.allocated': 'Groups allocated — {count} heat(s).',
  'groups.shuffledSuffix': '(shuffled)',
  'groups.allocateFailed': 'Failed to allocate groups',
  'groups.clearFailed': 'Failed to clear the heats',
  'groups.cleared': 'Groups cleared.',
  'groups.sheetDownloadFailed': 'Failed to download the marking sheet(s)',
  'groups.loadingEvent': 'Loading event…',
  'groups.layoutPreset': '{groupSize} per group · {sheet} sheet',
  'groups.heading': 'Heats & sheets',
  'groups.sheetFormat': 'Sheet format',
  'groups.shuffleAthletes': 'Shuffle athletes',
  'groups.allocating': 'Allocating…',
  'groups.clearing': 'Clearing…',
  'groups.downloadAllSheets': 'All heats PDF ({sheet})',
  'groups.loadingRosters': 'Loading heat rosters…',
  'groups.noGroupsHint': 'Use “Allocate groups” to lay out heats of {groupSize}.',
  'groups.directToFinalNote':
    'This event is run straight to a final, so there is no final to draw. Its groups are only there to split the field across the marking sheets.',
  'groups.untickDirectToFinal': 'Untick “direct to final” on the event',
  'groups.allocatedSummary': '{athletes} athletes in {groups} heat(s)',
  'groups.downloadSheet': 'Download {sheet} PDF',
  'groups.noAthletes': 'No athletes in this heat yet.',
  'groups.finalTitle': 'The final',
  'groups.finalHint':
    'Short sprint: the top {count} from the heats go through to the final, which has its own marks and its own marking sheet.',
  'groups.finalDrawn': 'Drawn',
  'groups.finalNotDrawn': 'Not drawn yet',
  'groups.finalPreview': 'Preview the final',
  'groups.finalPreviewing': 'Loading the qualifiers…',
  'groups.finalDraw': 'Draw the final',
  'groups.finalDrawing': 'Drawing the final…',
  'groups.finalRedraw': 'Re-draw the final',
  'groups.finalRedrawConfirm':
    'Re-draw the final? Every mark already recorded in the final will be discarded.',
  'groups.finalRemove': 'Remove the final',
  'groups.finalRemoveConfirm':
    'Remove the final? Every mark already recorded in the final will be discarded.',
  'groups.finalDrawnNotice': 'Final drawn — {count} athletes go through.',
  'groups.finalRemovedNotice': 'Final removed.',
  'groups.finalMarksCleared': '{count} mark(s) recorded in the final were discarded.',
  'groups.finalPreviewFailed': 'Failed to load the final preview',
  'groups.finalDrawFailed': 'Failed to draw the final',
  'groups.finalRemoveFailed': 'Failed to remove the final',
  'groups.finalNoQualifiers': 'No heat marks recorded yet — the final is ranked on them.',
  'groups.qualifiers': 'Who would qualify',
  'groups.heatMark': 'Heat mark',

  /* ---------------- admin: a student’s entries ---------------- */
  'entries.title': 'Event entries',
  'entries.subtitle': 'Enter events for a student who cannot enter them himself or herself.',
  'entries.adminActing': 'Administrator entry — acting for {name} ({studentId})',
  'entries.notSelf':
    'You are entering on this student’s behalf. The entries belong to the student, and the quota is theirs.',
  'entries.backToRegister': 'Back to the register',
  'entries.loadFailed': 'Failed to load the student’s entries',
  'entries.loading': 'Loading the student’s entries…',
  'entries.quotaPanel': 'Entry quota',
  'entries.placesLeft': '{count} place(s) left',
  'entries.currentEntries': 'Current entries',
  'entries.noEntries': 'This student has not entered any events yet.',
  'entries.addEvent': 'Add an event',
  'entries.addHint': 'Only enabled events in this student’s own division and grade are listed.',
  'entries.alreadyEntered': 'Already entered',
  'entries.reEnter': 'Re-enter',
  'entries.removeConfirm': 'Remove {name}’s entry to {event}?',
  'entries.confirmedOnly': 'Only a confirmed entry can be removed.',
  'entries.noEligible': 'Every event in this student’s division has already been entered.',
  'entries.addedNotice': 'Entered {name} in {event}.',
  'entries.removedNotice': 'Removed {name}’s entry to {event}.',
  'entries.addFailed': 'Could not add the entry',
  'entries.removeFailed': 'Could not remove the entry',
  'entries.gradeNotAllowed':
    'This is the {grade} grade event and the student is in the {mine} grade.',

  /* ---------------- admin: teachers ---------------- */
  'teachers.title': 'Teacher accounts',
  'teachers.subtitle':
    'The staff list, and the classes each teacher may help a student in.',
  'teachers.loadFailed': 'Failed to load the teachers',
  'teachers.importTools': 'Staff list upload',
  'teachers.subtitleHint':
    'One row per teacher: username, name and classes are required; email and password are optional. Separate several classes with ; , | or 、 — for example 1A;3B.',
  'teachers.uploadFile': 'Teacher list (.csv / .xlsx)',
  /* The rehearsal is the first of the two steps and changes nothing. */
  'teachers.rehearse': 'Rehearse — check the file',
  'teachers.rehearsing': 'Checking…',
  'teachers.dryRunTitle': 'Rehearsal — nothing has been saved',
  'teachers.dryRunHint':
    'This is what the file would do. No account is created, changed or locked until you save it below.',
  'teachers.dryRunBanner': 'Rehearsal — {created} would be created, {updated} updated, {failed} failed.',
  'teachers.apply': 'Save these accounts',
  'teachers.applying': 'Saving…',
  'teachers.applyConfirm':
    'Create or update these teacher accounts and replace their class lists?',
  'teachers.applied':
    'Saved — {created} created, {updated} updated, {failed} failed, {classes} class assignment(s).',
  'teachers.applyFailed': 'Failed to save the teacher accounts',
  'teachers.discard': 'Discard the rehearsal',
  'teachers.discarded': 'Nothing was saved.',
  'teachers.classes': 'Classes they may help',
  'teachers.classesAssigned': 'Class assignments',
  'teachers.passwordRule': 'Password rule',
  'teachers.credentialsIssued': 'Logins to note down ({count})',
  'teachers.credentialsHint':
    'A new password is known only here, once. Download the credentials sheet to keep it.',
  'teachers.supplied': 'From the file',
  'teachers.derived': 'Generated',
  'teachers.searchPlaceholder': 'Username or name',
  'teachers.noMatch': 'No teachers match the current filters.',
  'teachers.loadingTeachers': 'Loading the teachers…',
  'teachers.none': 'No teacher account has been uploaded yet.',
  'teachers.enabled': 'Active',
  'teachers.disabled': 'Disabled',
  'teachers.noClass': 'No classes assigned',
  'teachers.editClasses': 'Edit classes',
  'teachers.classesPlaceholder': 'e.g. 1A;3B',
  'teachers.classesSaved': 'Classes for {name} saved: {classes}',
  'teachers.classesSaveFailed': 'Failed to save the classes',
  'teachers.classesRequired':
    'Enter at least one class — a teacher with none can help nobody.',
  'teachers.template': 'Upload template',
  'teachers.credentials': 'Credentials CSV',
  'teachers.downloadFailed': 'Failed to download {label}',
  /*
   * The list endpoint does not carry a teacher's classes; the credentials sheet
   * is the only response that spells them out, so it is read back for this
   * column and the screen says so rather than looking arbitrarily blank.
   */
  'teachers.classesFromSheet':
    'This list does not carry the classes, so they are read from the credentials sheet.',
  'teachers.classesUnknown': 'The classes could not be read from the credentials sheet.',

  /* ---------------- teacher: helping a student ---------------- */
  'teacher.title': 'Help a student',
  'teacher.subtitle':
    'Enter or withdraw events for a student in one of your classes, on their behalf.',
  'teacher.myClasses': 'My classes',
  'teacher.noClasses': 'You have no classes assigned, so you cannot help any student yet.',
  'teacher.noClassesHint':
    'Ask the school office to assign you the classes you take. Until then the server refuses every entry you attempt, on your behalf.',
  'teacher.adminNoClasses':
    'No class on the register has a student in it yet, so there is nobody to help.',
  'teacher.classFilter': 'Class',
  'teacher.allClasses': 'All my classes',
  'teacher.sexFilter': 'Division',
  'teacher.allSexes': 'All divisions',
  'teacher.gradeFilter': 'Grade',
  'teacher.noMatchingStudents': 'No student in your classes matches these filters.',
  'entries.noMatchingEvents': 'No event matches these filters.',
  'users.emailOptional': 'Optional',
  'teacher.studentsTitle': 'Students I may help',
  'teacher.studentCount': '{count} student(s)',
  'teacher.noStudents': 'No student is in the classes assigned to you.',
  'teacher.loadFailed': 'Failed to load your classes',
  'teacher.studentsLoadFailed': 'Failed to load the students',
  'teacher.loading': 'Loading your classes…',
  'teacher.loadingStudents': 'Loading the students…',
  'teacher.entries': 'Entries',
  'teacher.acting': 'Teacher entry — acting for {name} ({studentId})',
  'teacher.backToStudents': 'Back to my students',
  'teacher.entriesLoadFailed': 'Failed to load the student’s entries',
  'teacher.entriesLoading': 'Loading the student’s entries…',
  'teacher.notYours':
    'This student is not in one of your classes, so you cannot enter or withdraw anything for them.',
  'teacher.entryRules': 'The entry rules',
  'teacher.division': 'Division',
  'teacher.eventGrade': 'Grade',
  'teacher.ownClassesOnly': 'You may only help students in your own classes.',
  'teacher.quotaRule':
    'A student may enter {track} track event(s) and {field} field event(s). This student has {trackLeft} track and {fieldLeft} field place(s) left.',
  'teacher.rulesHint':
    'Only this student’s own division and grade are listed, and each event below shows both. An entry they withdrew can be entered again — the old entry is revived, not duplicated.',
  'teacher.withdrawnReEnter': 'A withdrawn entry can be entered again.',
  'teacher.gradeNotAllowed':
    'This is the {grade} grade event and the student is in the {mine} grade.',
  'teacher.addedNotice': 'Entered {name} in {event}.',
  'teacher.removedNotice': 'Removed {name}’s entry to {event}.',
  'teacher.addFailed': 'Could not add the entry',
  'teacher.removeFailed': 'Could not remove the entry',
  'teacher.removeConfirm': 'Remove {name}’s entry to {event}?',

  /* ---------------- relay teams ---------------- */
  'relay.boardTitle': 'Relay team board',
  'relay.subtitle':
    'Divide the relay into form or house teams, then name a runner for each leg and set the running order.',
  'relay.loading': 'Loading the relay board…',
  'relay.loadFailed': 'Failed to load the relay teams',
  'relay.notRelay': 'This event is not a relay, so it has no relay teams.',
  'relay.kind': 'Relay teams',
  'relay.kindForm': 'Form relay (one team per class)',
  'relay.kindHouse': 'House relay (one team per house)',
  'relay.kindUndivided': 'Undivided — no teams',
  'relay.kindHint':
    'A form relay gives one team per class — of the form it is scoped to on the relay events page, taken across that form’s grades (1A, 1B, 1C and 1D), or of its own grade when no form is set; a house relay gives one team per house of that grade. An undivided relay has no teams at all.',
  'relay.kindLockedHint':
    'The server refuses a change of kind while the event still has teams, because those teams hold real selections. Remove them on the relay board first.',
  'relay.legsPerTeam': 'Legs per team',
  'relay.reservesAllowed': 'Allow reserves past the legs',
  'relay.reservesHint':
    'With reserves allowed a team may name up to twice its legs — four runners and four reserves for a 4x100M.',
  'relay.undividedTitle': 'This relay is undivided',
  'relay.undividedHint':
    'An undivided relay has no derived teams. Set the relay kind on the event to Form or House to derive the roster’s class or house teams and fill them here.',
  'relay.setKind': 'Set the relay kind on the event',
  'relay.derive': 'Derive the roster’s class or house teams',
  'relay.deriving': 'Deriving…',
  'relay.deriveTitle': 'Derive the class or house teams from the roster',
  'relay.deriveExplanation':
    'This makes the roster’s own teams and nothing else: one team per class on a form relay, one team per house on a house relay, taken from the register. It is how a form or house relay gets its teams, and each team is then filled from its own Add a runner list. A team made by hand through the relay team API is left alone by a derive: it is not one class’s and not one house’s, so it is never matched, renamed or dropped here.',
  'relay.derivePrune': 'Also drop empty teams that are no longer on the roster',
  'relay.derived':
    'Derived — {created} team(s) created, {kept} kept, {pruned} dropped, from {eligible} eligible students.',
  'relay.derivedKeptWithRunners':
    ' {count} team(s) were kept only because somebody runs in them.',
  'relay.deriveFailed': 'Failed to derive the relay teams',
  'relay.removeAll': 'Remove every team',
  'relay.removeAllConfirm':
    'Remove every relay team of this event, with their runners? This cannot be undone. It is what frees the event to change what kind of relay it is.',
  'relay.removedAll': 'Removed {count} relay team(s).',
  'relay.removeAllFailed': 'Failed to remove the relay teams',
  'relay.teamCount': '{count} team(s)',
  'relay.complete': 'Complete',
  'relay.legsFilled': '{filled} of {legs} legs filled',
  'relay.noRunners': 'No runner named yet.',
  'relay.leg': 'Leg',
  'relay.reserve': 'Reserve',
  'relay.runners': 'Runners',
  'relay.addRunner': 'Add a runner',
  'relay.addRunnerHint':
    'The register’s own students for this team are listed — the event’s division and its form or grade, in this team’s class or house, and nobody already running in this event. A runner you remove comes back here.',
  'relay.add': 'Add',
  'relay.adding': 'Adding…',
  'relay.added': 'Added {name} to {team}.',
  'relay.addFailed': 'Could not add the runner',
  'relay.noCandidates': 'No eligible student is left to add to this team.',
  'relay.remove': 'Remove',
  'relay.removeConfirm':
    'Remove {name} from {team}? They go back to the Add a runner list below.',
  'relay.removed': 'Removed {name} from {team} — they are offered again under Add a runner.',
  'relay.removeFailed': 'Could not remove the runner',
  'relay.orderHint': 'Leg 1 runs first. Move a runner up or down, then save the order.',
  'relay.moveUp': 'Move up',
  'relay.moveDown': 'Move down',
  'relay.saveOrder': 'Save the order',
  'relay.savingOrder': 'Saving…',
  'relay.orderSaved': 'Running order saved for {team}.',
  'relay.orderUnsaved': 'Unsaved order',
  'relay.orderFailed': 'Failed to save the running order',
  'relay.openBoard': 'Relay teams',
  'relay.backToGroups': 'Back to heats & sheets',
  'relay.backToRelays': 'Back to the relay events',
  'relay.backToTeacher': 'Back to my students',

  /*
   * Making a team out of the students who applied. The school confirmed the flow:
   * a teacher ticks any applicants, **types the team's own name** and creates that
   * team in one action — a team that is not a class and not a house. The roster's
   * class and house teams are still made by a derive, which is a separate thing and
   * is labelled as such.
   */
  'relay.applicantsTitle': 'Students who applied',

  /* making one team by hand, out of exactly the ticked students */
  'relay.handMade': 'Hand-made team',
  'relay.handMadeHint':
    'This team was made by hand, not derived from the register: it is not one class’s and not one house’s, so it carries no form or house key. A later derive never touches it — it is never matched, renamed or dropped by one.',
  'relay.derivedForm': 'Derived class team',
  'relay.derivedHouse': 'Derived house team',
  'relay.form': 'Form',
  'relay.team': 'Team',
  'relay.teamsTitle': 'The teams',
  'relay.noTeams':
    'No team has been made for this event yet. Derive the roster’s class or house teams above, then fill each one from its own Add a runner list.',
  'relay.undividedTeamsHint':
    'An undivided relay has no derived teams, so a derive has nothing to make here. An administrator sets the event’s relay kind to Form or House, and the class or house teams can then be derived and filled on this board.',
  'relay.undividedTeacherHint':
    'This relay is undivided, so it has no teams yet: an administrator sets the event’s relay kind to Form or House, and its class or house teams can then be derived and filled here.',
  'relay.shortTeams': '{count} team(s) short of a full relay',
  'relay.shortTeamsWarn':
    'These teams cannot run as they stand — {names}. A relay team must end up with four runners; a team with fewer is saved but incomplete, and its sheet is not a relay that can be run.',
  'relay.shortOfLegs': 'Short — {missing} runner(s) needed',
  'relay.incompleteWarn':
    'This team has {filled} of its {legs} runners, so it cannot run yet. Add the rest from this team’s own Add a runner list until all {legs} legs are filled.',
  'relay.allComplete': 'Every team is complete',
  'relay.reserveCount': '{count} reserve(s)',
  'relay.unnamed': 'Unnamed team',
  'relay.named': 'Named by hand',
  'relay.rename': 'Rename the team',
  'relay.teamName': 'Team name on the sheet',
  'relay.saveName': 'Save the name',
  'relay.renamed': 'The team is now called {team}.',
  'relay.renameFailed': 'Could not rename the team',
  'relay.renameHint':
    'This is the name the marking sheet and the mark grid are keyed on. Two teams of one race cannot share a name, and a name of more than 40 characters is refused.',

  /* ---------------- every relay event, and one-click teams ---------------- */
  /*
   * The relay programme is two families on two pages: the form class relays — one
   * team per class of a form, Forms 1 to 6, filtered by form — and the grade house
   * relays — one team per grade × house, Grades A to C, filtered by grade.
   */
  'relayEvents.formTitle': 'Form class relays',
  'relayEvents.formSubtitle':
    'The class relays: one team per class of the form the relay is scoped to — Forms 1 to 6, each class of that form across every grade. Filter by form to see one at a time, and make the ones the programme is missing from the grids above.',
  'relayEvents.houseTitle': 'Grade house relays',
  'relayEvents.houseSubtitle':
    'The house relays: one team per grade × house — Grades A to C. Filter by grade to see one at a time, and make the ones the programme is missing from the grid above.',
  'relayEvents.filterForm': 'Form:',
  'relayEvents.filterGrade': 'Grade:',
  'relayEvents.allForms': 'All forms',
  'relayEvents.allGrades': 'All grades',
  'relayEvents.groupNoForm': 'No form — the event’s own grade only',
  'relayEvents.noFormRelays':
    'No class relay is on the programme yet. The grids above make every class relay the programme is missing, each with its class teams made from the register.',
  'relayEvents.noHouseRelays':
    'No house relay is on the programme yet. The grid above makes every house relay the programme is missing, each with its grade × house teams made from the register.',
  'relayEvents.filterEmpty': 'No relay of this family matches the filter.',
  /*
   * The class relays as a form x division grid — **one grid per distance**, because
   * the school runs both a 4x100M and a 4x400M on every form class — and the one
   * press that makes the ones a grid is missing.
   *
   * A grid's heading names its distance: `4x100M Relay — class relays`. The distance
   * is the server's own label for the event type, read off a relay that already runs
   * it, so the page never spells the two distances out itself.
   */
  'relayEvents.gridTitle': '{type} — class relays',
  'relayEvents.createMissingCount': '{have} of {wanted} class relays are here',
  'relayEvents.createMissingHint':
    'A class relay is one per form and division, and the school runs both distances at form level: the 4x100M and the 4x400M, each of them for Forms 1 to 6, boys and girls. Each grid below makes the ones its own distance is missing — the class rule, scoped to that form, with its class teams made at the same time — so pressing a button twice creates nothing the second time.',
  'relayEvents.createMissingCell': 'missing',
  'relayEvents.createMissingRule':
    'A new relay is made at its own grid’s distance and scoped to its own form, so a 4x400M grid only ever makes a 4x400M. It is named after the other division’s relay of that form at that distance where there is one, and by the server otherwise. It takes the grade the school already files that form’s relay under, or — for a form with no relay at all — the grade that form’s age band runs: Forms 1 and 2 are C, 3 and 4 are B, 5 and 6 are A.',
  'relayEvents.createMissingNone': 'Every class relay of the programme is already here.',
  'relayEvents.createMissingButton': 'Create the {count} missing relay(s)',
  'relayEvents.createMissingDone': 'Created {count} relay(s), each with its teams.',
  'relayEvents.createMissingNext':
    'Open a relay’s board to add a student to one of its teams, or remove one again.',
  'relayEvents.createMissingFailed': 'Some relays were not created:',
  'relayEvents.createMissingOneFailed': '{name}: {reason}',
  'relayEvents.createMissingDivideFailed':
    '{name} was created, but its teams could not be made: {reason} — open its board below and make them there.',

  /*
   * The other half of the same panel, on the grade house page: the **twelve** house
   * relays the programme should hold — the 4x100M and the 4x400M, each of them for
   * Grades A to C, boys and girls.
   */
  'relayEvents.gridTitleHouse': '{type} — house relays',
  'relayEvents.createMissingCountHouse': '{have} of {wanted} house relays are here',
  'relayEvents.createMissingHouseHint':
    'A house relay is one per grade and division, and the school runs both distances at house level: the 4x100M and the 4x400M, each of them for Grades A to C, boys and girls. Each grid below makes the ones its own distance is missing — the house rule, in that grade, with its house teams made at the same time — so pressing a button twice creates nothing the second time.',
  'relayEvents.createMissingHouseRule':
    'Each relay is made in its own grade — C Grade’s relay is the C grade relay — and takes only its event type from the other relay of that grade, so a grade’s boys and girls relays are the same race. A grade with no relay at all is made as a 4x100M and named by the system.',
  'relayEvents.loading': 'Loading the relay events…',
  'relayEvents.loadFailed': 'Failed to load the relay events',
  'relayEvents.listTitle': 'The programme’s relay events',
  'relayEvents.listHint':
    'A relay with no teams yet is the normal starting state: it has simply not been divided. Reading each event’s board is what tells us how many teams it holds.',
  'relayEvents.eventCount': '{count} relay event(s)',
  'relayEvents.readingBoards': 'Reading each event’s teams…',
  'relayEvents.boardFailed': 'Failed to read this event’s relay board',
  'relayEvents.boardFailedTitle': 'This event’s teams could not be read:',
  'relayEvents.countUnknown': 'unknown',
  'relayEvents.notReadyTitle': 'This relay is not ready to run:',
  'relayEvents.notReadyTeams':
    'It has {count} team(s). A relay needs at least {needed}, each with a runner on every leg.',
  'relayEvents.rulesTitle': 'The two ways a relay is divided',
  'relayEvents.rulesHint':
    'The division belongs to the event, not to this page, so the buttons below set the event’s own setting before they make anything.',
  'relayEvents.formRule':
    'one team per class — of the form the relay is scoped to, taken across that form’s grades (1A, 1B, 1C, 1D), or of this event’s own grade when it is scoped to no form.',
  'relayEvents.houseRule':
    'one team per house within the event’s grade — for example C Grade Yellow.',
  'relayEvents.kindIsStoredOnTheEvent':
    'Both rules are the event’s relay kind, held on the event itself — the same setting the event form and the per-event board show.',
  'relayEvents.adminClearHint':
    'Making the teams by one rule after the other leaves the first rule’s teams in place: the server refuses to change an event’s kind while it still has teams, and says so. An event that already holds teams is therefore changed from its own board, where they can be removed first.',
  'relayEvents.teacherLimits':
    'A teacher may open any relay’s board and place their own classes’ students. Creating a relay, deleting a relay, setting its kind and printing marking sheets are administrator actions, so those controls are held back here.',
  'relayEvents.teacherPrintLimit':
    'Printing a marking sheet is an administrator action, so it is not offered here.',
  'relayEvents.anyForm': 'This grade only',
  'relayEvents.formN': 'Form {form}',
  'relayEvents.makeFailed': 'Failed to make the relay teams',
  'relayEvents.currentTeams': 'The teams of this event',
  'relayEvents.printSheets': 'Print the marking sheets',
  'relayEvents.printRun': 'Open the print run',
  'relayEvents.printFailed': 'Failed to print the marking sheets',
  'relayEvents.deleteRelay': 'Delete this relay',
  'relayEvents.deleteRelayConfirm':
    'Delete the relay “{name}” — the relay itself, with its {count} team(s) and the runners named on them, and its entries? This cannot be undone.',
  'relayEvents.deleteRelayConfirmUnknown':
    'Delete the relay “{name}” — the relay itself, with its teams and the runners named on them, and its entries? This cannot be undone.',

  /* ---------------- the teacher's way into a relay board ---------------- */
  'teacher.relayTitle': 'Relay events',
  'teacher.relayHint':
    'Open a relay event to see its teams and fill them: every team’s own list offers the register’s students for its class or house, and a runner can be removed and put back as often as you like. A relay is either a class relay (one team per class) or a house relay (one team per house of the event’s grade); you may place your own classes’ students, while every team of the event is shown so you can see who is running with whom.',
  'teacher.relayEmpty': 'The programme has no relay event yet.',
  'teacher.relayAll': 'See every relay event',

  /* ---------------- backups ---------------- */
  'backups.title': 'Backups',
  'backups.subtitle':
    'Every season backup held by the server. A backup keeps the entries, heats, final places, marks and school records of the moment it was taken.',
  'backups.loading': 'Loading the backups…',
  'backups.loadFailed': 'Failed to load the backups',
  'backups.none': 'There is no backup yet. A season reset writes one before it deletes anything.',
  'backups.count': '{count} backup(s)',
  'backups.name': 'File',
  'backups.size': 'Size',
  'backups.taken': 'Taken',
  'backups.contents': 'Contents',
  'backups.contentsLine': '{enrollments} entries · {groups} heats · {results} marks · {records} records',
  'backups.unreadable': 'This file could not be read as a season backup',
  'backups.download': 'Download',
  'backups.downloading': 'Downloading…',
  'backups.downloadFailed': 'Failed to download the backup',
  'backups.restore': 'Restore',
  'backups.restoring': 'Restoring…',
  'backups.restoreConfirm':
    'RESTORE THIS BACKUP? Every entry, heat, final place, recorded mark and school record now in the system is DELETED and replaced with the contents of {name}. This cannot be undone. Students, events and school years are kept.',
  'backups.restoreFailed': 'The backup was NOT restored',
  'backups.restoreResult':
    'Restored {name} — {groups} heat(s), {enrollments} entry/entries, {finalEntries} final place(s), {results} mark(s) and {records} record(s) put back.',
  'backups.restoreSkipped':
    ' {count} row(s) in the file were skipped: their student or event no longer exists.',
  'backups.restoreOutcome': 'Restore outcome',
  'backups.afterRestore':
    'The season now holds what the backup held. Take a fresh backup before changing anything else if the state you replaced may still be wanted.',
  'backups.resetBackedUp':
    'Season reset. Backup written first: {file} ({bytes} bytes). Removed {enrollments} entries, {groups} heats, {results} marks.',
  'backups.latest': 'Newest backup',
  'backups.openBackups': 'All backups',
  'admin.backups': 'Backups',
  'admin.backupsHint': 'See what each backup holds, download one, or restore one.',
  'nav.backups': 'Backups',

} as const;

/** Traditional Chinese (Hong Kong) wording. */
const zh: Record<keyof typeof en, string> = {
  'nav.events': '比賽項目',
  'nav.myEntries': '我的報名',
  'nav.results': '成績',
  'nav.admin': '管理',
  'nav.marks': '輸入成績',
  'nav.print': '列印記錄表',
  'nav.records': '學校紀錄',
  'nav.championships': '錦標賽',
  'nav.settings': '設定',
  'nav.users': '使用者',
  'nav.sportDay': '運動會',
  'nav.login': '登入',
  'nav.logout': '登出',
  'nav.language': '語言',
  'nav.teachers': '教師帳戶',
  'nav.helpStudents': '協助學生報名',
  'nav.relayFormEvents': '班際接力',
  'nav.relayHouseEvents': '社際接力',

  'common.loading': '載入中…',
  'common.saving': '儲存中…',
  'common.cancel': '取消',
  'common.close': '關閉',
  'common.refresh': '重新載入',
  'common.reset': '重設',
  'common.print': '列印',
  'common.search': '搜尋',
  'common.all': '全部',
  'common.none': '無',
  'common.actions': '操作',
  'common.retry': '再試一次',

  'role.ADMIN': '管理員',
  'role.MANAGER': '幹事',
  'role.STUDENT': '學生',
  'role.USER': '教職員',
  'role.TEACHER': '教師',
  'role.HELPER': '輸入助理',

  'final.state.NONE': '沒有決賽',
  'final.state.DIRECT': '直接決賽',
  'final.state.NOT_DRAWN': '決賽尚未抽籤',
  'final.state.DRAWN': '已抽決賽',
  'final.notDrawnHint': '初賽成績為先。請先輸入初賽成績，再據此抽出決賽。',
  'final.noStageHint': '此項目沒有決賽，只有初賽成績表可供處理。',

  'category.TRACK': '徑項',
  'category.FIELD': '田項',
  'category.RELAY': '接力',
  'sex.MALE': '男子組',
  'sex.FEMALE': '女子組',
  'grade.A': 'A 組（17 歲或以上）',
  'grade.B': 'B 組（15–16 歲）',
  'grade.C': 'C 組（14 歲或以下）',
  'grade.short.A': 'A',
  'grade.short.B': 'B',
  'grade.short.C': 'C',
  'sheet.A5': 'A5',
  'sheet.A4': 'A4',
  'unit.M': '米',
  'unit.s': '秒',
  /* Legacy unit words — see the note in the `en` block. */
  'unit.seconds': '秒',
  'unit.metres': '米',

  'auth.signIn': '登入',
  'auth.signingIn': '登入中…',
  'auth.username': '使用者名稱',
  'auth.password': '密碼',
  'auth.studentId': '學號',
  'auth.studentHint':
    '學生以學號登入。密碼為出生日期（yyyyMMdd）加上班別及班號，例如 2010-03-15 出生、5A 班 12 號的學生，密碼是 201003155A12。',
  'auth.staffHint': '教職員請以管理員或幹事帳戶登入。',
  'auth.loginFailed': '無法登入',

  'events.title': '比賽項目',
  'events.quota': '報名限額',
  'events.enter': '報名',
  'events.withdraw': '取消報名',
  'events.entered': '已報名',
  'events.entries': '報名人數',
  'events.groupSize': '每組人數',
  'events.sheet': '紙張',
  'events.date': '日期',

  'my.title': '我的報名',
  'my.heat': '組別',
  'my.lane': '線道',
  'my.notAllocated': '尚未分組',
  'my.noEntries': '你尚未報名任何項目。',

  'eventFilters.sex': '組別',
  'eventFilters.allSexes': '全部組別',
  'eventFilters.grade': '級別',
  'eventFilters.allGrades': '全部級別',
  'eventFilters.category': '類別',
  'eventFilters.allCategories': '全部類別',
  'eventFilters.eventType': '項目',
  'eventFilters.allEventTypes': '全部項目',

  'print.title': '列印記錄表',
  'print.subtitle': '每組一張記錄表，供工作人員填寫成績。短跑用 A5，其餘用 A4。',
  'print.division': '組別',
  'print.category': '類別',
  'print.event': '項目',
  'print.allEvents': '所有已分組項目',
  'print.preview': '預覽',
  'print.downloadAll': '下載所有符合的記錄表',
  'print.gradeDownloadHint': '整批下載涵蓋所有級別。請先清除級別篩選，或於下方逐項下載。',
  'print.downloadEvent': '下載此項目的記錄表',
  'print.heats': '組數',
  'print.athletes': '人數',
  'print.noHeats': '尚未分組。',
  'print.needHeats': '請先分組，然後回來列印。',
  'print.finalNotDrawn': '此項目設有決賽，但決賽尚未抽籤。',
  'print.finalNotDrawnHint': '初賽成績為先。請先輸入初賽成績並抽出決賽，之後才可列印決賽記錄表。',
  'print.allHeldBack':
    '只要範圍內仍有項目等待決賽抽籤，整批下載便無法進行：只印初賽會令人以為決賽記錄表遺失了。',
  'print.browserPrint': '在瀏覽器列印',
  'print.columns': '每張記錄表有五欄：學號、姓名、級別、成績、備註。成績及備註留空供工作人員填寫。',
  'print.allDivisions': '全部組別',
  'print.allCategories': '全部類別',
  'print.matchingCount': '符合項目 {count} 個，共 {heats} 組',
  'print.noMatching': '沒有符合篩選條件的項目。',
  'print.relaysNotReady': '另有 {count} 個接力項目未列出：接力項目尚未齊隊，暫未能記錄成績。',
  'print.previewTitle': '預覽 — {name}',
  'print.downloadHeat': '下載{label}',
  'print.previewFailed': '無法開啟預覽',
  'print.finalCount': '決賽：{count} 人',

  'marks.title': '輸入成績',
  'marks.subtitle': '直接在表格輸入成績，一次儲存全部。',
  'marks.pickEvent': '項目',
  'marks.pickGroup': '組別',
  'marks.eventGrade': '項目級別',
  'marks.pickGrade': '運動員級別',
  'marks.allGroups': '全部組別',
  'marks.allGrades': '全部級別',
  'marks.studentId': '學號',
  'marks.name': '姓名',
  'marks.grade': '級別',
  'marks.class': '班別',
  'marks.form': '級別',
  'marks.house': '社',
  'marks.heat': '組別',
  'marks.lane': '線道',
  'marks.record': '成績',
  'marks.standard': '標準 {standard}',
  'marks.belowStandard': '未達標準（{standard}）',
  'marks.heatRecord': '初賽',
  'nav.standards': '標準',
  'nav.students': '學生',
  'nav.manageEvents': '管理項目',
  'nav.groupSetup': '設定',
  'nav.groupRun': '比賽當日',
  'nav.groupReview': '查閱',
  'nav.groupAdmin': '管理',
  'standards.title': '達標標準',
  'standards.intro':
    '運動員必須達到的標準成績。只有 400 米或以上的徑賽，以及所有田項，才設有標準 — 400 米以下的短跑不設標準，接力亦不設。徑賽成績等於或低於標準即為達標；田項成績等於或高於標準即為達標。留空即代表不設標準，完成後儲存整頁一次。',
  'standards.empty': '本屆賽事沒有任何項目設有達標標準。',
  'standards.listed': '共有 {count} 個項目設有達標標準。',
  'standards.event': '項目',
  'standards.standard': '標準',
  'standards.unit': '單位',
  'standards.none': '不設標準',
  'standards.saveAll': '儲存標準',
  'standards.savedCount': '已儲存 {count} 項標準。',
  'standards.failedCount': '有 {count} 項標準未能儲存：',
  'standards.saveFailed': '未能儲存',
  'standards.invalid': '{event}："{value}" 不是大於零的數字',
  'standards.fixFirst': '未儲存任何變更，請先修正以下標準：',
  'standards.nothingToSave': '沒有標準被更改。',
  'standards.blankSkipped': '只會儲存你更改過的輸入框。',
  'standards.changedCount': '已更改 {count} 項',
  'standards.defaultsTitle': '預設標準 — 每個組別及項目類型設定一次',
  'standards.defaultsIntro':
    '為每個項目類型、組別及性別設定一次達標標準 —「400M、A 組、男子」— 該組合的所有項目都會繼承這個標準。新建項目會自動套用。',
  'standards.defaultsKey':
    '鍵值為項目類型 × 組別 × 性別。男子與女子刻意分開：兩者是獨立賽事，各有自己的達標標準，因此男子的預設標準永遠不會套用到女子項目。',
  'standards.grade': '組別',
  'standards.sex.MALE': '男子',
  'standards.sex.FEMALE': '女子',
  'standards.inheritors': '由 {count} 個項目繼承',
  'standards.noEvents': '本屆賽事沒有此項目',
  'standards.invalidDefault': '{event} {grade}："{value}" 不是大於零的數字',
  'standards.saveDefaults': '儲存預設標準',
  'standards.applyTitle': '將預設標準套用到現有項目',
  'standards.applyIntro':
    '儲存預設標準只會影響日後新建的項目，不會改變賽程上已有的項目。這一步才是更新現有項目，刻意分開以免誤改資料。請先預覽：預覽會列明會更改多少個項目，並且不會寫入任何資料。',
  'standards.applyHandSet':
    '在單一項目上輸入的標準是該組別的例外，一般套用永遠不會覆蓋它 — 只會計算並報告。只有「覆蓋所有項目」才會取代它。',
  'standards.preview': '預覽',
  'standards.applyInherited': '套用到跟隨預設標準的項目',
  'standards.applyAll': '覆蓋所有項目（包括手動設定）',
  'standards.previewCount':
    '預覽：將會更改 {count} 個項目，{kept} 項手動設定的標準保持不變。尚未寫入任何資料。',
  'standards.appliedCount': '已更新 {count} 個項目，{kept} 項手動設定的標準保持不變。',
  'standards.keptNote': '有 {count} 個項目持有手動設定的標準，這些項目未被改動。',
  'standards.applyFailed': '未能套用預設標準',
  'standards.wouldBecome': '將改為',
  'standards.exceptionsTitle': '例外 — 逐一項目設定',
  'standards.exceptionsIntro':
    '若某項賽事不採用所屬組別的預設標準，可在此為它設定專屬標準。在此輸入的數字只屬於這個項目，套用預設標準時永遠不會被取代。',
  'standards.showExceptions': '顯示全部 {count} 個設有標準的項目',
  'standards.hideExceptions': '隱藏逐項輸入框',
  'standards.source': '來源',
  'standards.fromDefault': '組別預設',
  'standards.handSet': '手動設定',
  'marks.remark': '備註',
  'marks.unit': '單位',
  'marks.timeFormat': '分.秒.毫秒',
  'marks.timePlaceholder': '1.04.123',
  'marks.timeBadShape':
    '{who}："{value}" 不是有效時間 — 請輸入 分.秒.毫秒，例如 1.04.123、0.48.123 或 48.123。',
  'marks.timeSecondsLimit':
    '{who}：時間的秒數部分必須少於 60 — 2 分 15 秒請輸入 2.15.000，不要輸入 1.75.000。',
  'marks.attempt': '第 {n} 次',
  'marks.best': '最佳',
  'marks.fieldHint': '田項 — 每人三次試擲。依次輸入，失敗留空，以最佳成績為準。',
  'marks.marked': '已記錄 {marked} / {total}',
  'marks.saveAll': '儲存全部成績',
  'marks.clearMark': '清除',
  'marks.saveSummary': '已儲存 {saved} 項，清除 {cleared} 項，未變更 {skipped} 項，失敗 {failed} 項',
  'marks.leaderboard': '成績排名',
  'marks.rank': '名次',
  'marks.pickEventFirst': '請選擇項目以開始輸入成績。',
  'marks.thinEventsHidden': '另有 {count} 個項目未列出：只有多於一名運動員報名才值得記錄成績。',
  'marks.noMarkableEvents': '目前沒有項目有多於一名運動員報名，暫無成績可記錄。',
  'marks.relaysNotReady': '另有 {count} 個接力項目未列出：接力項目尚未齊隊，暫未能記錄成績。',
  'marks.blankSkipped': '留空的列不會被更改。',
  'marks.unsaved': '尚未儲存的變更',
  'marks.invalidMark': '{who}："{value}" 不是有效數字',
  'marks.remarkNeedsRecord': '{who}：備註需要先輸入成績',
  'marks.fixBeforeSaving': '未儲存任何變更，請先修正以下資料列：',
  'marks.loadFailed': '無法載入成績表',
  'marks.loadingSheet': '載入成績表…',
  'marks.saveFailed': '無法儲存成績',
  'marks.nothingToSave': '目前沒有需要儲存的變更。',
  'marks.saveErrors': '無法儲存的資料列',
  'marks.noRows': '沒有符合篩選條件的運動員。',
  'marks.filterHint': '更改組別或級別會重新從伺服器載入表格。',
  'marks.groupOption': '{label} · {count} 人',
  'marks.pickStage': '階段',
  'marks.stageHeat': '初賽',
  'marks.stageFinal': '決賽',
  'marks.stageUnavailable': '尚未可以輸入',
  'marks.finalHint': '決賽只有一場，不設初賽組別篩選。',
  'marks.finalNotDrawn': '決賽尚未抽籤。',
  'marks.finalNotDrawnHint': '請先到項目的分組頁面，由初賽前 8 名抽出決賽，然後回來輸入決賽成績。',
  'marks.openGroups': '前往分組及決賽',
  'marks.finalQualifiers': '決賽共 {count} 名選手',
  'marks.finalSheetHint': '決賽成績表 — 列印紙張 {sheet}',
  'marks.outcome': '結果狀態',
  'marks.outcomeResult': '成績',
  'marks.outcomeAbs': 'ABS 缺席',
  'marks.outcomeDq': 'DQ 取消資格',
  'marks.outcomeHint':
    'ABS 表示缺席，DQ 表示取消資格。選擇後會清除該列的成績及試擲紀錄，亦不會進行成績規則檢查。',

  'admin.title': '管理',
  'admin.students': '學生名冊',
  'admin.studentsHint': '上載名冊、核對級別、派發密碼。',
  'admin.events': '比賽項目',
  'admin.eventsHint': '建立標準項目，並啟用或停用。',
  'admin.seasonReset': '重設賽季',
  'admin.seasonResetConfirm': '確定刪除所有報名、分組及成績？學生及項目會保留。',
  'admin.results': '記錄單一成績',
  'admin.teachers': '教師帳戶',
  'admin.teachersHint': '上載教職員名單，並查看每位教師可協助的班別。',

  'students.title': '學生名冊',
  'students.referenceDate': '級別計算日期',
  'students.generateSample': '產生 600 位示範學生',
  'students.recomputeGrades': '重新計算級別',
  'students.total': '學生人數',
  'students.byGrade': '按級別',
  'students.credentials': '密碼表 CSV',
  'students.template': '上載範本',
  'students.sampleCsv': '示範名冊 CSV',
  'students.colId': '學號',
  'students.colName': '姓名',
  'students.colDob': '出生日期',
  'students.colAge': '年齡',
  'students.colHouse': '社',
  /* 班別所屬的級別 — 5D 即 Form 5 — 以及社及其簡寫。 */
  'students.form': '級別',
  'students.formValue': 'Form {form}',
  'students.houseCodeHint': '社的簡寫 — R、Y、B、G。學校未設定簡寫的社只顯示社名。',
  'students.importResult': '匯入結果',
  'students.created': '新增',
  'students.updated': '更新',
  'students.failed': '失敗',
  'students.rowErrors': '無法匯入的資料列',
  'students.row': '列',
  'students.entries': '報名',

  'adminEvents.title': '比賽項目',
  'adminEvents.createDefaults': '建立標準項目',
  'adminEvents.new': '新增項目',
  'adminEvents.enabled': '已啟用',
  'adminEvents.disabled': '已停用',
  'adminEvents.enable': '啟用',
  'adminEvents.disable': '停用',
  'adminEvents.delete': '刪除',
  'adminEvents.deleteConfirm': '確定刪除此項目、其報名及成績？',
  'adminEvents.directToFinalWarning':
    '此項目已分組或已抽決賽。現時更改賽制，會影響可能已經舉行的賽事程序。',

  'groups.title': '分組',
  'groups.allocate': '分組',
  'groups.clear': '清除所有分組',
  'groups.clearConfirm': '確定清除此項目的所有分組？',
  'groups.noGroups': '尚未分組。',
  'groups.noGroupsRelay':
    '接力項目以隊伍區分，而非分組 — 點名表每隊一行。請於接力隊伍編排頁面建立隊伍。',

  'results.title': '成績',
  'results.notes': '備註',
  'results.noResults': '尚未記錄成績。',

  'common.sex': '性別',
  'common.details': '詳情',
  'common.edit': '編輯',
  'common.processing': '處理中…',
  'common.creating': '建立中…',
  'common.upload': '上載',
  'common.uploading': '上載中…',
  'common.downloading': '下載中…',
  'common.downloaded': '已下載 {filename}',
  'common.saveChanges': '儲存變更',
  'common.backToAdmin': '返回管理',
  'common.backToEvents': '返回項目列表',

  'home.welcome': '歡迎，{name}！',
  'home.subtitle': '你今天想做甚麼？',
  'home.eventsDesc': '瀏覽及報名參加比賽項目',
  'home.resultsDesc': '查看排名及比賽成績',
  'home.myEntriesDesc': '管理你的報名紀錄',
  'home.adminTitle': '管理介面',
  'home.adminDesc': '管理項目、使用者及成績',
  'home.upcoming': '即將舉行的項目',

  'auth.loginTitle': '登入 SportDay',
  'auth.usernamePlaceholder': '教職員使用者名稱，或學號（例：S0003）',
  'auth.studentLogin': '學生登入',
  'auth.show': '顯示',
  'auth.hide': '隱藏',
  'auth.adminCredentials': '管理員：{username} / {password}',
  'auth.email': '電郵',
  'auth.fullName': '姓名',

  'events.loadFailed': '無法載入比賽項目',
  'events.loadingEvents': '載入比賽項目…',
  'events.enteredNotice': '已報名：{type} — {name}',
  'events.enterFailed': '無法報名 {name}',
  'events.withdrawConfirm': '確定退出 {name}？',
  'events.withdrawnNotice': '已退賽：{type} — {name}',
  'events.withdrawFailed': '無法退出 {name}',
  'events.fullCount': '名額已滿（{count}/{max}）',
  'events.trackQuotaFull': '徑項名額已滿（{used}/{max}）',
  'events.fieldQuotaFull': '田項名額已滿（{used}/{max}）',
  'events.manage': '管理項目',
  'events.trackUsed': '徑項 {used}/{max}',
  'events.fieldUsed': '田項 {used}/{max}',
  'events.remaining': '剩餘 — 徑項 {track} · 田項 {field}',
  'events.sexDivision': '性別組別',
  'events.noEventsDivision': '此組別暫無項目。',
  'events.place': '地點',
  'events.participants': '人數',
  'events.ungroupedCount': '（未分組 {count}）',
  'events.notFound': '找不到比賽項目',
  'events.name': '項目名稱',
  'events.description': '說明',
  'events.type': '項目',
  'events.maxParticipants': '名額上限',
  'events.sheetSize': '紙張大小',
  'events.maxEntries': '每人最多報名項目',
  'events.shortSprint': '短跑（分線道）',
  'events.lanesPhotoFinish': '線道及終點攝影',
  'events.directToFinal': '直接決賽',
  'events.directToFinalHint': '此項目由自己一輪賽事決定成績。取消勾選即可先跑初賽，再跑決賽。',
  'events.directToFinalForced': '只有 60M、100M、200M 及 400M 可分初賽及決賽。',
  'events.directToFinalAutomatic': '系統自動設定',
  'events.directToFinalAutomaticHint':
    '此項目只有 8 名或以下運動員報名，決賽會與初賽同一批選手，因此系統自動設為直接決賽。若報名人數回升，會自動回復初賽及決賽。',
  'events.heatsAndFinal': '初賽 + 決賽',
  'events.format': '賽制',
  'events.visibleToStudents': '開放給學生',
  'events.event': '項目',
  'events.backToEvent': '返回項目',
  'events.browse': '瀏覽項目',

  /* ---------------- grade, seen from the entry list ---------------- */
  'events.gradeNotAllowed': '這是 {grade} 組項目，而你屬於 {mine} 組。',
  'events.gradeSummary': '{total} 個項目中有 {allowed} 個屬於 {grade} 組，只顯示這些項目',
  'events.gradeUnknown':
    '你的個人資料未載有級別，因此顯示所有項目。其他級別的項目，伺服器仍會拒絕。',

  /* ---------------- the programme by date ---------------- */
  'events.programmeDate': '比賽日期',
  'events.allDates': '所有日期',
  'events.dateOption': '{date} · {count} 項',
  'events.dateOptionToday': '{date} · {count} 項 · 今日',
  'events.dateOptionPast': '{date} · {count} 項 · 已舉行',
  'events.dateSummary': '{date} 共 {count} 項比賽',
  'events.allDatesSummary': '全部 {count} 項比賽',

  /* ---------------- the programme by school year ---------------- */
  'events.schoolYear': '學年',
  'events.allYears': '所有學年',
  'events.yearOption': '{year} · {count} 個項目',
  'events.yearOptionCurrent': '{year} · {count} 個項目 · 現行',
  'events.yearOptionClosed': '{year} · {count} 個項目 · 報名已截止',
  'events.viewingYear': '正在查看 {year} — {name}',
  'events.viewingAllYears': '顯示所有學年',
  'events.entriesClosedYear': '{year} 的報名已截止。你仍可瀏覽項目表。',

  'my.loadFailed': '無法載入你的報名',
  'my.withdrawnNotice': '已退賽：{name}',
  'my.withdrawFailed': '無法取消報名',
  'my.reEnteredNotice': '已重新報名：{name}',
  'my.reEnterFailed': '無法重新報名',
  'my.loadingEntries': '載入你的報名…',
  'my.reEnter': '重新報名',
  'my.confirmed': '已確認',
  'my.withdrawn': '已退出',
  'my.entryGradeNotAllowed':
    '你的級別已與此項目不符。現有報名仍然有效 — 但重新報名將被拒絕。',

  'results.athlete': '運動員',
  'results.eventResults': '比賽成績',
  'results.selectEvent': '選擇項目',
  'results.chooseEvent': '請選擇項目…',
  'results.noResultsEvent': '此項目尚未記錄成績。',
  'results.recordResult': '記錄成績',
  'results.recordTitle': '記錄比賽成績',
  'results.recorded': '成績已記錄。',
  'results.recordFailed': '無法記錄成績',
  'results.selectAthlete': '選擇運動員',
  'results.markPlaceholder': '例：10.123',
  'results.unitPlaceholder': '例：秒、米',
  'results.notesPlaceholder': '備註（可選）…',
  'results.tabEvent': '按項目',
  'results.tabPast': '過往項目',
  'results.pastHint': '今日或之前舉行的項目，最新的排最前。選擇項目即可查看賽果。',
  'results.pastHintYear': '所選學年中今日或之前舉行的項目。選擇項目即可查看賽果。',
  'results.noPastEventsYear': '此學年暫無過往項目。',
  'results.yearFilter': '學年',
  'results.selectPastEvent': '過往項目',
  'results.choosePastEvent': '請選擇過往項目…',
  'results.noPastEvents': '暫無過往項目。',
  'results.pastStandings': '名次',
  'results.pastNoResults': '此項目暫未記錄名次。',
  'results.newRecord': '破紀錄',
  'results.place': '名次',
  /* 成績欄：成績連單位寫成一個值（14.123秒、2.15.5秒、18.12米），不再分成兩欄。 */
  'results.result': '成績',
  'results.downloadEventPdf': '下載此項目的成績',
  'results.downloadAllPdf': '下載全部成績',
  'results.pdfNothingForEvent': '此項目尚未記錄成績，暫無可列印的內容。',
  'results.pdfNothingForProgramme': '尚未記錄任何成績，暫無可列印的內容。',
  'results.pdfFailed': '無法下載成績 PDF',
  'results.stage': '階段',
  'results.choose': '請選擇…',
  'results.pickEventFirst': '請選擇組別、級別及項目以查看成績。',
  'results.noStageResults': '此項目尚未記錄{stage}成績。',
  'results.noFinal': '此項目以初賽定勝負，並無決賽。',

  /* ---------------- school records ---------------- */
  'records.title': '學校紀錄',
  'records.subtitle': '各項目、各組別及級別的最佳成績。',
  'records.groupBy': '分組方式',
  'records.byCategory': '類別',
  'records.byEvent': '項目',
  'records.rebuild': '重建紀錄',
  'records.rebuilding': '重建中…',
  'records.rebuildConfirm': '確定根據已記錄的成績重建所有學校紀錄？現有紀錄將被取代。',
  'records.rebuilt': '紀錄已重建 — 由 {rebuilt} 項成績產生 {count} 項紀錄。',
  'records.rebuildFailed': '無法重建紀錄',
  'records.loadFailed': '無法載入學校紀錄',
  'records.count': '{count} 項紀錄',
  'records.empty': '尚未有學校紀錄。',
  'records.emptyHint': '請先在成績表輸入成績，然後重建紀錄。',
  'records.colEvent': '項目',
  'records.colDivision': '組別',
  'records.colGrade': '級別',
  'records.colMark': '最佳成績',
  'records.colHolder': '保持者',
  'records.colAchieved': '締造日期',
  'records.colPrevious': '舊紀錄',
  'records.previousLine': '{name} — {mark}{unit}（{date}）',
  'records.firstRecord': '首項紀錄',
  'records.colSource': '紀錄來源',
  'records.sourceBaseline': '人手輸入',
  'records.sourceResult': '由成績保持',
  'records.sourceNone': '尚未有紀錄',
  'records.colTypedIn': '人手輸入的成績',
  'records.noMark': '尚未有成績',
  'records.noTypedIn': '未有人手輸入',
  'records.baselineNote':
    '人手輸入的成績會一直保留：在有成績超越它之前，它仍然是紀錄，重建亦不會清除。',
  'records.baselineBeaten': '你輸入了 {mark} {unit}，其後已被成績超越。',
  'records.baselineHeld': '現時紀錄為人手輸入的成績。',
  'records.resultHeld': '此紀錄由已記錄的成績保持。',
  'records.editBaseline': '編輯人手輸入的成績',
  'records.fieldMark': '成績',
  'records.fieldUnit': '單位',
  'records.holderPlaceholder': '例：Chan Tai Man (2018)',
  'records.saveBaseline': '儲存人手輸入的成績',
  'records.baselineSaved': '已儲存人手輸入的成績。',
  'records.baselineSaveFailed': '無法儲存人手輸入的成績',
  'records.baselineMarkRequired': '請先輸入成績。',
  'records.clearBaseline': '清除人手輸入的成績',
  'records.clearBaselineConfirm':
    '確定清除 {event}（{division} · {grade}）人手輸入的成績？紀錄將改由成績決定。',
  'records.baselineCleared': '已清除人手輸入的成績 — 紀錄改由成績決定。',
  'records.baselineClearFailed': '無法清除人手輸入的成績',
  'records.seed': '補建缺少的紀錄',
  'records.seeding': '建立中…',
  'records.seeded': '已補建 {created} 項紀錄 — 共 {count} 項。',
  'records.seedNone': '所有紀錄均已存在 — 共 {count} 項。',
  'records.seedFailed': '無法補建紀錄',
  'records.rebuildKeepsBaseline': '重建會保留所有人手輸入的成績。',

  /* ---------------- championships ---------------- */
  'championships.title': '錦標賽',
  'championships.subtitle': '按參考日期或之前所有已計分項目，計算個人及社際總分。',
  'championships.referenceDate': '參考日期',
  'championships.eventsScored': '已計分項目',
  'championships.athletesScored': '已計分運動員',
  'championships.scoringStage': '計分階段',
  'championships.pointsScale': '分數表',
  'championships.loadFailed': '無法載入錦標賽成績',
  'championships.personalTitle': '個人錦標',
  'championships.personalEmpty': '尚無運動員取得分數。',
  'championships.housesTitle': '社際錦標',
  'championships.housesEmpty': '尚無社取得分數。',
  'championships.eventsTitle': '各項目名次',
  'championships.eventsEmpty': '尚無項目計分。',
  'championships.colRank': '名次',
  'championships.colAthlete': '運動員',
  'championships.colGrade': '級別',
  'championships.colClass': '班別',
  'championships.colHouse': '社',
  'championships.colPoints': '分數',
  'championships.colGold': '金牌',
  'championships.colSilver': '銀牌',
  'championships.colBronze': '銅牌',
  'championships.colEventsScored': '計分項目',
  'championships.colAthletes': '人數',
  'championships.leader': '領先',
  'championships.stageFinal': '決賽',
  'championships.stageHeat': '初賽',
  'championships.stageHint': '設有決賽的項目以決賽計分，其餘以初賽計分。',
  'championships.relayHint': '接力項目的名次標示為「接力」，只計入社際總分。',
  'championships.relay': '接力',
  'championships.relayTag': '接力',
  'championships.schoolRecord': '學校紀錄',
  'championships.noPlacings': '尚未記錄名次。',
  'championships.toggleEvent': '顯示名次',

  /* ---------------- admin: settings ---------------- */
  'settings.title': '設定',
  'settings.subtitle': '報名限額及錦標賽計分標準。',
  'settings.loadFailed': '無法載入設定',
  'settings.entryLimits': '報名限額',
  'settings.entryLimitsHint': '每位學生可報名的項目數目。',
  'settings.trackMaxEntries': '每位學生可報徑項數目',
  'settings.fieldMaxEntries': '每位學生可報田項數目',
  'settings.minOne': '報名限額最少為 1。',
  'settings.points': '分數',
  'settings.pointsHint':
    '個人名次由第一名計至最低計分名次；接力採用相同名次的三個分數。',
  'settings.individual': '個人',
  'settings.relay': '接力',
  'settings.pointsFirst': '第一名',
  'settings.pointsSecond': '第二名',
  'settings.pointsThird': '第三名',
  'settings.pointsTopPlace': '最低計分名次',
  'settings.pointsTop': '第四名至該名次的分數',
  'settings.currentScale': '現時分數表',
  'settings.save': '儲存設定',
  'settings.saved': '設定已儲存。',
  'settings.saveFailed': '無法儲存設定',
  'settings.reset': '還原預設值',
  'settings.resetConfirm': '確定將所有設定還原為預設值？',
  'settings.resetDone': '設定已還原為預設值。',
  'settings.resetFailed': '無法還原預設值',
  'settings.resetting': '還原中…',
  'settings.updatedAt': '最後更新 {date}',
  'settings.scale': '第一名 {first}、第二名 {second}、第三名 {third}、第四至{topPlace}名 {top} · 接力 {rfirst} / {rsecond} / {rthird}',
  'settings.scaleTopPlace': '第四至{topPlace}名 {top} · 接力同分 {rtop}',
  'settings.unexpectedError': '伺服器回應有誤',

  /* ---------------- admin: users ---------------- */
  'users.title': '使用者及角色',
  'users.subtitle': '教職員帳戶、角色及權限。密碼不會再次顯示。',
  'users.loadFailed': '無法載入帳戶',
  'users.studentsNotice': '學生帳戶不在此建立 — 由名冊匯入產生。',
  'users.studentsLink': '前往學生名冊',
  'users.createTitle': '建立帳戶',
  'users.createHint': '只可建立 ADMIN、MANAGER 及 USER。STUDENT 帳戶由名冊匯入產生。',
  'users.roleLabel': '角色',
  'users.create': '建立帳戶',
  'users.creating': '建立中…',
  'users.created': '已建立帳戶 {username}。',
  'users.createFailed': '無法建立帳戶',
  'users.password': '密碼',
  'users.passwordPlaceholder': '最少 6 個字元',
  'users.roleFilter': '角色',
  'users.statusFilter': '狀態',
  'users.allRoles': '全部角色',
  'users.allStatuses': '全部狀態',
  'users.searchPlaceholder': '使用者名稱或姓名',
  'users.enable': '啟用',
  'users.disable': '停用',
  'users.enabledNotice': '已啟用 {username}',
  'users.disabledNotice': '已停用 {username}',
  'users.statusFailed': '無法更改帳戶狀態',
  'users.delete': '刪除',
  'users.deleteConfirm': '確定刪除 {username}？其報名及成績會一併刪除。',
  'users.deletedNotice': '已刪除 {username}',
  'users.deleteFailed': '無法刪除帳戶',
  'users.count': '共 {count} 個帳戶',
  'users.noMatch': '沒有符合篩選條件的帳戶。',
  'users.validationFull': '請輸入使用者名稱、最少 6 個字元的密碼及電郵。',
  'users.email': '電郵',
  'users.fullName': '姓名',
  'users.passwordHint': '只顯示一次，不會儲存在瀏覽器。',

  'admin.seasonResetHint': '只清除報名、分組及成績 — 項目與學生名冊會保留。',
  'admin.seasonResetDone': '賽季已重設 — 報名、分組及成績已清除。',
  'admin.seasonResetFailed': '重設賽季失敗',
  'admin.resetting': '重設中…',
  'admin.createEventHint': '新增比賽項目',
  'admin.recordHint': '輸入比賽成績',
  'admin.leaderboardHint': '查看比賽成績',
  'admin.allUsers': '所有使用者',
  'admin.colId': '編號',
  'admin.colRole': '角色',
  'admin.colStatus': '狀態',
  'admin.active': '已啟用',

  /* ---------------- admin: sport day & school years ---------------- */
  'sportDay.title': '運動會及學年',
  'sportDay.subtitle': '學校本身的資料，以及每個學年的一場運動會。',
  'sportDay.loading': '載入運動會資料…',
  'sportDay.loadFailed': '無法載入學年',
  'sportDay.currentSummary': '現行學年：{year} · 報名開放中',
  'sportDay.currentSummaryClosed': '現行學年：{year} · 報名已截止',
  'sportDay.noCurrent': '目前沒有現行學年，學生暫未能報名任何項目。',
  'sportDay.yearsCount': '共 {count} 個學年',

  /* --- A. the school's details --- */
  'sportDay.schoolDetails': '學校資料',
  'sportDay.schoolDetailsHint': '這些資料會印在每張記錄表頂部，運動會前請先核對。',
  'sportDay.schoolName': '學校名稱（英文）',
  'sportDay.schoolNameZh': '學校名稱（中文）',
  'sportDay.address': '地址',
  'sportDay.principal': '校長',
  'sportDay.sportDayTitle': '運動會名稱（印在記錄表上）',
  'sportDay.sheetHeading': '每張記錄表頂部的資料',
  'sportDay.sheetHeadingHint': '你列印的每張記錄表，頂部都會印上學校資料及運動會名稱。',
  'sportDay.schoolSaved': '學校資料已儲存。',
  'sportDay.schoolFieldsHint': '報名限額及分數表仍然在設定頁面。',

  /* --- B. the school years --- */
  'sportDay.years': '學年',
  'sportDay.yearsHint': '每個學年一場運動會。現行學年就是學生可以報名的那一年。',
  'sportDay.noYears': '尚未建立任何學年。',
  'sportDay.year': '學年',
  'sportDay.name': '名稱',
  'sportDay.date': '運動會日期',
  'sportDay.eventCount': '項目數',
  'sportDay.entries': '報名',
  'sportDay.entriesOpen': '報名開放中',
  'sportDay.entriesClosed': '報名已截止',
  'sportDay.current': '現行',
  'sportDay.notCurrent': '非現行',
  'sportDay.enrollmentOpen': '學生可報名此學年',
  'sportDay.enrollmentOpenHint':
    '這個開關就是學生能否報名的關鍵。關閉後，此學年將不再接受任何新報名。',
  'sportDay.openEntries': '開放報名',
  'sportDay.closeEntries': '截止報名',
  'sportDay.enrollmentOpened': '已開放 {year} 的報名。',
  'sportDay.enrollmentClosed': '已截止 {year} 的報名。',
  'sportDay.enrollmentFailed': '無法更改報名狀態',
  'sportDay.viewProgramme': '查看項目表',
  'sportDay.activate': '設為現行學年',
  'sportDay.activating': '設定中…',
  'sportDay.activateHint': '設為學生可報名的學年，並關閉其他所有學年。',
  'sportDay.activateConfirm': '確定將 {year} 設為學生可報名的學年？其他學年將不再接受新報名。',
  'sportDay.activated': '{year} 現已設為現行學年，其他學年已關閉。',
  'sportDay.activateFailed': '無法設為現行學年',
  'sportDay.create': '新增學年',
  'sportDay.createHint': '新學年預設沒有項目，除非你複製舊學年的項目表。',
  'sportDay.createYear': '學年',
  'sportDay.createName': '名稱',
  'sportDay.createNamePlaceholder': '例：2027 運動會',
  'sportDay.defaultName': '{year} 運動會',
  'sportDay.createDate': '運動會日期',
  'sportDay.createOpen': '立即開放報名',
  'sportDay.createOpenHint': '預設關閉：新學年建立時報名為截止，以免誤收報名。',
  'sportDay.copyFrom': '項目表',
  'sportDay.copyFromNone': '建立空白的項目表',
  'sportDay.copyFromYear': '複製 {year} 的項目表',
  'sportDay.createSubmit': '建立學年',
  'sportDay.creating': '建立中…',
  'sportDay.created': '已建立 {name}。',
  'sportDay.createFailed': '無法建立學年',
  'sportDay.yearRequired': '請以四位數字輸入學年，例：2027。',
  'sportDay.nameRequired': '請輸入學年名稱。',
  'sportDay.dateRequired': '請輸入運動會日期。',
  'sportDay.edit': '編輯',
  'sportDay.editTitle': '編輯 {year}',
  'sportDay.editHint': '過往學年可隨時修正 — 其日期、名稱及備註都會保留作紀錄。',
  'sportDay.notes': '備註',
  'sportDay.notesPlaceholder': '關於此學年值得記錄的事項…',
  'sportDay.saveYear': '儲存學年',
  'sportDay.saved': '已儲存 {name}。',
  'sportDay.saveFailed': '無法儲存學年',
  'sportDay.delete': '刪除',
  'sportDay.deleting': '刪除中…',
  'sportDay.deleteConfirm': '確定刪除 {name}？此操作無法復原。',
  'sportDay.deleteConfirmWithEvents':
    '確定刪除 {name}？該學年仍有 {count} 個項目。請先刪除或移動這些項目，否則伺服器會拒絕。',
  'sportDay.deleted': '已刪除 {name}。',
  'sportDay.deleteFailed': '無法刪除學年',

  'students.loadFailed': '無法載入學生名冊',
  'students.chooseFileFirst': '請先選擇 .csv 或 .xlsx 檔案。',
  'students.uploadFailed': '上載失敗',
  'students.uploadFinished': '上傳完成 — 新增 {created}、更新 {updated}、失敗 {failed}。',
  'students.sampleGenerated': '已產生示範學生 — 新增 {created}、更新 {updated}、失敗 {failed}。',
  'students.sampleFailed': '無法產生示範學生',
  'students.recomputeFinished': '重新計算級別完成 — 更改 {changed} 項，參考日期 {date}（{counts}）。',
  'students.recomputeFailed': '無法重新計算級別',
  'students.downloaded': '已下載 {label} → {filename}',
  'students.downloadFailed': '無法下載 {label}',
  'students.gradeRule': '級別規則（以運動會當日年齡計算）',
  'students.gradeRuleLine': 'A 組：{a} · B 組：{b} · C 組：{c}',
  'students.importTools': '匯入與工具',
  'students.registerFile': '名冊檔案（.csv / .xlsx）',
  'students.generating': '產生中…',
  'students.computing': '計算中…',
  'students.downloadTokenNote': '下載需帶同登入權杖。',
  'students.batch': '批次',
  'students.file': '檔案',
  'students.gradeReference': '級別參考日',
  'students.message': '訊息',
  'students.searchPlaceholder': '學號 / 姓名',
  'students.classPlaceholder': '例：5A',
  'students.loadingStudents': '載入學生名冊…',
  'students.noMatch': '沒有符合篩選條件的學生。',

  /* --- the yearly roster upload and the lock preview --- */
  'students.completeList': '這是本學年完整的學生名單',
  'students.completeListHint':
    '勾選後，不在檔案中的學生都會被鎖定：他們將無法登入，亦無法被報名參加項目。若檔案只是學校的一部分，學校其餘學生都會被鎖定。',
  'students.completeListDone': '完整名單已匯入 — 新增 {created}、更新 {updated}、失敗 {failed}。',
  'students.previewLock': '預覽將被鎖定的學生',
  'students.previewing': '預覽中…',
  'students.lockPreviewTitle': '鎖定預覽 — 尚未作出任何更改',
  'students.lockPreviewHint': '這是將會發生的情況。在你於下方確認之前，不會鎖定任何人。',
  'students.wouldLock': '將被鎖定的學生',
  'students.lockTotal': '有 {count} 位學生不在此檔案中',
  'students.lockSampleNote': '顯示首 {shown} 位，共 {total} 位。',
  'students.lockConfirm': '立即鎖定這些學生',
  'students.locking': '鎖定中…',
  'students.lockDiscard': '放棄預覽',
  'students.lockDiscarded': '沒有鎖定任何學生。',
  'students.lockApplied': '已鎖定 {locked} 位學生；已恢復 {unlocked} 位舊生為有效。',
  'students.lockAppliedOnly': '已鎖定 {locked} 位學生。',
  'students.unlockedOnly': '已恢復 {count} 位舊生為有效。',
  'students.rosterUsesServerDate': '完整名單上載會自行計算級別參考日期。',
  'students.lockConsequence':
    '被鎖定的學生會保留所有報名、成績及紀錄 — 只是被隱藏，並非刪除。在解除鎖定前，他們無法登入，亦無法被報名參加項目。',
  'students.lockFailed': '無法鎖定學生',
  'students.lockMissing': '鎖定上次上載未包括的學生',
  'students.lockMissingHint':
    '若上載時沒有勾選「完整名單」，可在此鎖定上次檔案未包括的所有學生。',
  'students.lockMissingConfirm':
    '確定鎖定上次上載未包括的所有學生？他們會保留報名及成績，但無法登入及被報名參加項目。',
  'students.lockMissingDone': '已鎖定批次 {batch} 未包括的 {locked} 位學生 — 其中 {now} 位為本次新增。',
  'students.lockMissingFailed': '無法鎖定未包括的學生',
  'students.lockingMissing': '鎖定中…',

  /* --- locked students on the register --- */
  'students.statusFilter': '狀態',
  'students.statusActive': '有效',
  'students.locked': '已鎖定',
  'students.lock': '鎖定',
  'students.unlock': '解除鎖定',
  'students.lockedNotice': '已鎖定 {name}',
  'students.unlockedNotice': '已解除鎖定 {name}',
  'students.lockStatusFailed': '無法更改鎖定狀態',
  'students.lockedNote': '被鎖定的學生會保留其報名、成績及紀錄 — 只是被隱藏，並非刪除。',

  'adminEvents.enabledNotice': '{name} 已啟用',
  'adminEvents.disabledNotice': '{name} 已停用',
  'adminEvents.statusFailed': '無法更改項目狀態',
  'adminEvents.deleteConfirmNamed': '確定刪除「{name}」？此操作無法復原。',
  'adminEvents.deletedNotice': '已刪除 {name}',
  'adminEvents.deleteFailed': '無法刪除項目',
  'adminEvents.pickDate': '請先選擇運動會日期。',
  'adminEvents.defaultsCreated': '已建立標準項目 — 新增 {created} 項，{date} 共 {total} 項。',
  'adminEvents.inclField': '（包括田項）',
  'adminEvents.trackOnly': '（只含徑項）',
  'adminEvents.defaultsFailed': '無法建立標準項目',
  'adminEvents.allEvents': '所有項目',
  'adminEvents.ungrouped': '未分組',
  'adminEvents.defaultCatalogue': '預設項目表',
  'adminEvents.defaultCatalogueHint':
    '建立標準比賽項目（徑項 + 田項），新項目預設為啟用。',
  'adminEvents.sportDay': '運動會日期',
  'adminEvents.searchPlaceholder': '名稱 / 項目',
  'adminEvents.noMatch': '沒有符合篩選條件的項目。',
  'adminEvents.heatsCount': '{count} 組',
  'adminEvents.createTitle': '新增比賽項目',
  'adminEvents.createFailed': '無法新增項目',
  'adminEvents.updateFailed': '無法更新項目',
  'adminEvents.editTitle': '編輯項目',
  'adminEvents.toggleEnable': '切換啟用狀態',
  'adminEvents.create': '建立項目',
  'adminEvents.year': '學年',
  'adminEvents.pastYear': '過往學年',
  'adminEvents.defaultsYearNote': '新項目會加入現行學年。如要處理過往學年，請先切換上方的學年。',
  'adminEvents.viewingYear': '正在查看 {year} — {name}',
  'adminEvents.viewingAllYears': '顯示所有學年',
  'adminEvents.gradeHint':
    '每個項目只屬於一個級別，不同級別絕不會同場排名。標準項目表亦非每個項目都設三個級別：5000M 只設 A 組，1500M 及 110M 跨欄不設 C 組。',
  'adminEvents.gradeNotRun': '{type} 不設 {grade} 組。請改選此項目設有的組別，或更改項目類型。',

  'groups.loadFailed': '無法載入分組',
  'groups.allocated': '已分組 — 共 {count} 組。',
  'groups.shuffledSuffix': '（已隨機抽籤）',
  'groups.allocateFailed': '無法分組',
  'groups.clearFailed': '無法清除分組',
  'groups.cleared': '已清除分組。',
  'groups.sheetDownloadFailed': '無法下載記錄表',
  'groups.loadingEvent': '載入項目…',
  'groups.layoutPreset': '每組 {groupSize} 人 · {sheet} 點名表',
  'groups.heading': '分組與點名表',
  'groups.sheetFormat': '點名表格式',
  'groups.shuffleAthletes': '隨機分組',
  'groups.allocating': '分組中…',
  'groups.clearing': '清除中…',
  'groups.downloadAllSheets': '下載全部點名表 PDF（{sheet}）',
  'groups.loadingRosters': '載入運動員名單…',
  'groups.noGroupsHint': '按「分組」以每組 {groupSize} 人編排組別。',
  'groups.directToFinalNote':
    '此項目直接進行決賽，因此沒有決賽需要抽籤。分組只是為了把參賽者分到各張記錄表。',
  'groups.untickDirectToFinal': '在項目設定中取消勾選「直接決賽」',
  'groups.allocatedSummary': '已編入 {athletes} 位運動員 · {groups} 組',
  'groups.downloadSheet': '下載 {sheet} PDF',
  'groups.noAthletes': '此組暫無運動員。',
  'groups.finalTitle': '決賽',
  'groups.finalHint': '短跑項目：初賽前 {count} 名晉級決賽，決賽有獨立成績及獨立記錄表。',
  'groups.finalDrawn': '已抽籤',
  'groups.finalNotDrawn': '尚未抽籤',
  'groups.finalPreview': '預覽決賽名單',
  'groups.finalPreviewing': '載入晉級名單…',
  'groups.finalDraw': '抽出決賽',
  'groups.finalDrawing': '正在抽決賽…',
  'groups.finalRedraw': '重新抽決賽',
  'groups.finalRedrawConfirm': '確定重新抽決賽？決賽已記錄的成績將會被刪除。',
  'groups.finalRemove': '移除決賽',
  'groups.finalRemoveConfirm': '確定移除決賽？決賽已記錄的成績將會被刪除。',
  'groups.finalDrawnNotice': '已抽出決賽 — 共 {count} 名運動員晉級。',
  'groups.finalRemovedNotice': '已移除決賽。',
  'groups.finalMarksCleared': '已刪除 {count} 項決賽成績。',
  'groups.finalPreviewFailed': '無法載入決賽預覽',
  'groups.finalDrawFailed': '無法抽出決賽',
  'groups.finalRemoveFailed': '無法移除決賽',
  'groups.finalNoQualifiers': '尚未有初賽成績 — 決賽名單依初賽成績排列。',
  'groups.qualifiers': '預計晉級名單',
  'groups.heatMark': '初賽成績',

  /* ---------------- admin: a student’s entries ---------------- */
  'entries.title': '代學生報名',
  'entries.subtitle': '為無法自行報名的學生代為報名。',
  'entries.adminActing': '管理員代為報名 — 學生 {name}（{studentId}）',
  'entries.notSelf': '你正代此學生報名。報名屬於該學生，名額限額亦以該學生計算。',
  'entries.backToRegister': '返回學生名冊',
  'entries.loadFailed': '無法載入該學生的報名',
  'entries.loading': '載入該學生的報名…',
  'entries.quotaPanel': '報名限額',
  'entries.placesLeft': '剩餘 {count} 個名額',
  'entries.currentEntries': '現時報名',
  'entries.noEntries': '此學生尚未報名任何項目。',
  'entries.addEvent': '新增項目',
  'entries.addHint': '只列出此學生所屬組別及級別的已啟用項目。',
  'entries.alreadyEntered': '已報名',
  'entries.reEnter': '重新報名',
  'entries.removeConfirm': '確定取消 {name} 在「{event}」的報名？',
  'entries.confirmedOnly': '只有已確認的報名可以取消。',
  'entries.noEligible': '此學生所屬組別的項目均已報名。',
  'entries.addedNotice': '已為 {name} 報名「{event}」。',
  'entries.removedNotice': '已取消 {name} 在「{event}」的報名。',
  'entries.addFailed': '無法新增報名',
  'entries.removeFailed': '無法取消報名',
  'entries.gradeNotAllowed': '這是 {grade} 組項目，而該學生屬於 {mine} 組。',

  /* ---------------- admin: teachers ---------------- */
  'teachers.title': '教師帳戶',
  'teachers.subtitle': '教職員名單，以及每位教師可協助學生的班別。',
  'teachers.loadFailed': '無法載入教師帳戶',
  'teachers.importTools': '教職員名單上載',
  'teachers.subtitleHint':
    '每位教師一列：使用者名稱、姓名及班別為必填，電郵及密碼為選填。多個班別請以 ; 、 , 、 | 或 、 分隔 — 例如 1A;3B。',
  'teachers.uploadFile': '教師名單（.csv / .xlsx）',
  'teachers.rehearse': '預演 — 檢查檔案',
  'teachers.rehearsing': '檢查中…',
  'teachers.dryRunTitle': '預演 — 尚未儲存任何變更',
  'teachers.dryRunHint':
    '這是此檔案將會造成的結果。在你於下方儲存之前，不會建立、更改或鎖定任何帳戶。',
  'teachers.dryRunBanner': '預演 — 將新增 {created} 個、更新 {updated} 個、失敗 {failed} 個。',
  'teachers.apply': '儲存這些帳戶',
  'teachers.applying': '儲存中…',
  'teachers.applyConfirm': '確定建立或更新這些教師帳戶，並取代其班別清單？',
  'teachers.applied':
    '已儲存 — 新增 {created} 個、更新 {updated} 個、失敗 {failed} 個，共 {classes} 項班別指派。',
  'teachers.applyFailed': '無法儲存教師帳戶',
  'teachers.discard': '放棄預演',
  'teachers.discarded': '沒有儲存任何變更。',
  'teachers.classes': '可協助的班別',
  'teachers.classesAssigned': '班別指派',
  'teachers.passwordRule': '密碼規則',
  'teachers.credentialsIssued': '需記錄的登入資料（{count}）',
  'teachers.credentialsHint': '新密碼只會在此顯示一次。請下載密碼表以作保存。',
  'teachers.supplied': '由檔案提供',
  'teachers.derived': '系統產生',
  'teachers.searchPlaceholder': '使用者名稱或姓名',
  'teachers.noMatch': '沒有符合篩選條件的教師。',
  'teachers.loadingTeachers': '載入教師帳戶…',
  'teachers.none': '尚未上載任何教師帳戶。',
  'teachers.enabled': '有效',
  'teachers.disabled': '已停用',
  'teachers.noClass': '未指派任何班別',
  'teachers.editClasses': '編輯班別',
  'teachers.classesPlaceholder': '例：1A;3B',
  'teachers.classesSaved': '已儲存 {name} 的班別：{classes}',
  'teachers.classesSaveFailed': '無法儲存班別',
  'teachers.classesRequired': '請輸入最少一個班別 — 沒有班別的教師無法協助任何學生。',
  'teachers.template': '上載範本',
  'teachers.credentials': '密碼表 CSV',
  'teachers.downloadFailed': '無法下載 {label}',
  'teachers.classesFromSheet': '此清單並不載有班別，因此班別是從密碼表讀取的。',
  'teachers.classesUnknown': '無法從密碼表讀取班別。',

  /* ---------------- teacher: helping a student ---------------- */
  'teacher.title': '協助學生報名',
  'teacher.subtitle': '為你所屬班別的學生代為報名或取消報名。',
  'teacher.myClasses': '我的班別',
  'teacher.noClasses': '你未獲指派任何班別，因此暫時無法協助任何學生。',
  'teacher.noClassesHint':
    '請聯絡校務處指派你任教的班別。在此之前，伺服器會拒絕你代為提出的所有報名。',
  'teacher.adminNoClasses': '名冊上尚未有任何班別設有學生，因此暫無可協助的對象。',
  'teacher.classFilter': '班別',
  'teacher.allClasses': '我的全部班別',
  'teacher.sexFilter': '組別',
  'teacher.allSexes': '全部組別',
  'teacher.gradeFilter': '級別',
  'teacher.noMatchingStudents': '你的班別中沒有學生符合這些篩選條件。',
  'entries.noMatchingEvents': '沒有項目符合這些篩選條件。',
  'users.emailOptional': '可留空',
  'teacher.studentsTitle': '我可協助的學生',
  'teacher.studentCount': '{count} 位學生',
  'teacher.noStudents': '你所獲指派的班別中沒有學生。',
  'teacher.loadFailed': '無法載入你的班別',
  'teacher.studentsLoadFailed': '無法載入學生',
  'teacher.loading': '載入你的班別…',
  'teacher.loadingStudents': '載入學生…',
  'teacher.entries': '報名',
  'teacher.acting': '教師代為報名 — 學生 {name}（{studentId}）',
  'teacher.backToStudents': '返回我的學生',
  'teacher.entriesLoadFailed': '無法載入該學生的報名',
  'teacher.entriesLoading': '載入該學生的報名…',
  'teacher.notYours': '此學生並非你所屬班別的學生，因此你無法代他報名或取消報名。',
  'teacher.entryRules': '報名規則',
  'teacher.division': '組別',
  'teacher.eventGrade': '級別',
  'teacher.ownClassesOnly': '你只可協助自己所屬班別的學生。',
  'teacher.quotaRule':
    '每位學生可報 {track} 項徑項及 {field} 項田項。此學生尚餘 {trackLeft} 個徑項及 {fieldLeft} 個田項名額。',
  'teacher.rulesHint':
    '只列出此學生所屬組別及級別的項目，下方每個項目均顯示兩者。學生已退出的報名可以重新報名 — 舊有報名會被恢復，不會重複。',
  'teacher.withdrawnReEnter': '已退出的報名可以重新報名。',
  'teacher.gradeNotAllowed': '這是 {grade} 組項目，而該學生屬於 {mine} 組。',
  'teacher.addedNotice': '已為 {name} 報名「{event}」。',
  'teacher.removedNotice': '已取消 {name} 在「{event}」的報名。',
  'teacher.addFailed': '無法新增報名',
  'teacher.removeFailed': '無法取消報名',
  'teacher.removeConfirm': '確定取消 {name} 在「{event}」的報名？',

  /* ---------------- relay teams ---------------- */
  'relay.boardTitle': '接力隊伍編排',
  'relay.subtitle': '把接力項目分為班際或社際隊伍，然後為每一棒安排運動員並設定接力次序。',
  'relay.loading': '載入接力隊伍…',
  'relay.loadFailed': '無法載入接力隊伍',
  'relay.notRelay': '此項目並非接力項目，因此沒有接力隊伍。',
  'relay.kind': '接力隊伍',
  'relay.kindForm': '班際（每班一隊）',
  'relay.kindHouse': '社際（每社一隊）',
  'relay.kindUndivided': '不分隊 — 沒有隊伍',
  'relay.kindHint':
    '班際接力每班一隊 — 依接力項目頁所選定的級別，涵蓋該級別各年級的班別（1A、1B、1C、1D）；未選定級別時，則為該項目所屬年級的每班一隊。社際接力則按該級別的每個社各出一隊。不分隊的接力項目完全沒有隊伍。',
  'relay.kindLockedHint':
    '此項目仍有隊伍時，伺服器會拒絕更改接力類別，因為這些隊伍載有實際的選手安排。請先在接力編排頁面移除所有隊伍。',
  'relay.legsPerTeam': '每隊棒數',
  'relay.reservesAllowed': '容許後備（多於棒數）',
  'relay.reservesHint': '容許後備時，每隊最多可安排棒數兩倍的人數 — 4x100M 即四名跑手及四名後備。',
  'relay.undividedTitle': '此接力項目尚未分隊',
  'relay.undividedHint':
    '未分隊的接力項目沒有由名冊產生的隊伍。請在項目設定中選擇班際或社際，即可由名冊產生班際或社際隊伍，並在此加入跑手。',
  'relay.setKind': '在項目設定中選擇接力類別',
  'relay.derive': '由名冊產生班際或社際隊伍',
  'relay.deriving': '產生中…',
  'relay.deriveTitle': '由名冊產生班際或社際隊伍',
  'relay.deriveExplanation':
    '此按鈕只會產生名冊本身的隊伍：班際接力每班一隊，社際接力每社一隊，全部取自名冊。班際或社際接力就是這樣產生隊伍的，之後可在各隊下方的「新增跑手」名單加入跑手。若隊伍是經接力隊伍 API 自建的，產生功能不會觸及它：它不屬於任何班別或社，因此永遠不會被配對、改名或刪除。',
  'relay.derivePrune': '同時刪除名冊上已不存在且沒有成員的空隊伍',
  'relay.derived': '已產生 — 新增 {created} 隊、保留 {kept} 隊、刪除 {pruned} 隊，合資格學生 {eligible} 人。',
  'relay.derivedKeptWithRunners': ' 另有 {count} 隊因已有成員而保留。',
  'relay.deriveFailed': '無法產生接力隊伍',
  'relay.removeAll': '移除所有隊伍',
  'relay.removeAllConfirm':
    '確定移除此項目的所有接力隊伍及其成員？此操作無法復原。移除後才可更改接力類別。',
  'relay.removedAll': '已移除 {count} 隊接力隊伍。',
  'relay.removeAllFailed': '無法移除接力隊伍',
  'relay.teamCount': '{count} 隊',
  'relay.complete': '已齊人',
  'relay.legsFilled': '已安排 {filled} / {legs} 棒',
  'relay.noRunners': '尚未安排跑手。',
  'relay.leg': '棒次',
  'relay.reserve': '後備',
  'relay.runners': '跑手',
  'relay.addRunner': '新增跑手',
  'relay.addRunnerHint':
    '此表列出註冊名單中可加入此隊的學生：項目的組別及其級別／級別範圍、此隊所屬的班別或社，且未在此項目中作賽。被移除的跑手會回到此名單。',
  'relay.add': '新增',
  'relay.adding': '新增中…',
  'relay.added': '已把 {name} 加入 {team}。',
  'relay.addFailed': '無法新增跑手',
  'relay.noCandidates': '此隊已沒有合資格的學生可加入。',
  'relay.remove': '移除',
  'relay.removeConfirm': '確定把 {name} 從 {team} 移除？移除後會回到下方的「新增跑手」名單。',
  'relay.removed': '已把 {name} 從 {team} 移除 — 可在「新增跑手」再次加入。',
  'relay.removeFailed': '無法移除跑手',
  'relay.orderHint': '第一棒先跑。把跑手上下移動，然後儲存次序。',
  'relay.moveUp': '上移',
  'relay.moveDown': '下移',
  'relay.saveOrder': '儲存次序',
  'relay.savingOrder': '儲存中…',
  'relay.orderSaved': '已儲存 {team} 的接力次序。',
  'relay.orderUnsaved': '次序尚未儲存',
  'relay.orderFailed': '無法儲存接力次序',
  'relay.openBoard': '接力隊伍',
  'relay.backToGroups': '返回分組及點名表',
  'relay.backToRelays': '返回接力項目',
  'relay.backToTeacher': '返回我的學生',

  /* 把報名接力的學生編成隊伍。伺服器沒有「以指定學生開設一隊」的端點，因此本頁的流程是：
     先由名冊產生班際或社際隊伍，再把已勾選的學生加入其所屬隊伍，最後為隊伍命名。 */
  'relay.applicantsTitle': '報名學生',

  /* 以勾選的學生親手建立一隊 */
  'relay.handMade': '自建隊伍',
  'relay.handMadeHint':
    '此隊為自建隊伍，並非由名冊產生：它不屬於任何班別或社，因此沒有班別或社的識別值。日後再按產生隊伍時不會影響此隊 — 它永遠不會被配對、改名或刪除。',
  'relay.derivedForm': '由名冊產生的班際隊伍',
  'relay.derivedHouse': '由名冊產生的社際隊伍',
  'relay.form': '級別',
  'relay.team': '隊伍',
  'relay.teamsTitle': '隊伍',
  'relay.noTeams': '此項目尚未有任何隊伍。請在上方由名冊產生班際或社際隊伍，然後在各隊下方的「新增跑手」名單加入跑手。',
  'relay.undividedTeamsHint':
    '未分隊的接力項目沒有由名冊產生的隊伍，因此沒有可產生的隊伍。請管理員先把此項目的接力類別設為班際或社際，即可在此產生並加入跑手。',
  'relay.undividedTeacherHint':
    '此接力項目尚未分隊，因此未有隊伍：請管理員先把此項目的接力類別設為班際或社際，即可在此產生並加入跑手。',
  'relay.shortTeams': '{count} 隊人數不足',
  'relay.shortTeamsWarn':
    '以下隊伍按現狀不能出賽 — {names}。接力隊伍必須有四位跑手；人數不足的隊伍會獲儲存但尚未齊人，其點名表不能作接力賽使用。',
  'relay.shortOfLegs': '尚欠 {missing} 名跑手',
  'relay.incompleteWarn':
    '此隊現有 {legs} 棒中的 {filled} 名跑手，尚未能出賽。請在此隊下方的「新增跑手」名單加入跑手，直至 {legs} 棒全部齊人。',
  'relay.allComplete': '所有隊伍均已齊人',
  'relay.reserveCount': '{count} 名後備',
  'relay.unnamed': '未命名隊伍',
  'relay.named': '自訂名稱',
  'relay.rename': '更改隊名',
  'relay.teamName': '點名表上的隊名',
  'relay.saveName': '儲存隊名',
  'relay.renamed': '隊伍現名為 {team}。',
  'relay.renameFailed': '無法更改隊名',
  'relay.renameHint':
    '此名稱是點名表及成績輸入表所依據的名稱。同一賽事不能有兩隊同名，亦不接受超過 40 字的名稱。',

  /* ---------------- 教師前往接力編隊的入口 ---------------- */
  /* ---------------- every relay event, and one-click teams ---------------- */
  'relayEvents.formTitle': '班際接力',
  'relayEvents.formSubtitle':
    '班際接力：接力項目所屬年級的每班一隊 — 中一至中六，該年級各級別的班別都會成隊。可用年級篩選，逐個查看；尚未建立的接力可在上方方格一次過建立。',
  'relayEvents.houseTitle': '社際接力',
  'relayEvents.houseSubtitle':
    '社際接力：每個組別 × 每社一隊 — A 至 C 組。可用組別篩選，逐個查看；尚未建立的接力可在上方方格一次過建立。',
  'relayEvents.filterForm': '年級：',
  'relayEvents.filterGrade': '組別：',
  'relayEvents.allForms': '所有年級',
  'relayEvents.allGrades': '所有組別',
  'relayEvents.groupNoForm': '未設年級 — 只限本級',
  'relayEvents.noFormRelays':
    '本年度暫時未有班際接力項目。上方方格會建立所有尚未建立的班際接力，並同時由名冊編好班隊。',
  'relayEvents.noHouseRelays':
    '本年度暫時未有社際接力項目。上方方格會建立所有尚未建立的社際接力，並同時由名冊編好各組別 × 各社的隊伍。',
  'relayEvents.filterEmpty': '此類接力項目沒有符合篩選條件的項目。',
  'relayEvents.gridTitle': '{type} — 班際接力',
  'relayEvents.createMissingCount': '已有 {have} / {wanted} 個班際接力',
  'relayEvents.createMissingHint':
    '班際接力按年級與組別組成，而學校於各年級均設兩個距離：4x100M 及 4x400M，中一至中六各有男子及女子接力。下方每個方格會建立其所屬距離尚未有的項目 — 套用班際規則、設定該年級，並同時編好班隊，因此再按一次不會重複建立。',
  'relayEvents.createMissingCell': '尚未建立',
  'relayEvents.createMissingRule':
    '新接力會以其方格所屬的距離建立，並設定其所屬年級，因此 4x400M 的方格只會建立 4x400M。若該年級於同一距離已有另一組別的接力，名稱會沿用該名稱並改為另一組別，否則由系統產生。組別則沿用該年級現有接力所屬的組別；若該年級完全未有接力，會採用該年級的組別 — 中一、中二為 C 組，中三、中四為 B 組，中五、中六為 A 組。',
  'relayEvents.createMissingNone': '本年度所有班際接力項目均已建立。',
  'relayEvents.createMissingButton': '建立 {count} 個尚未建立的接力',
  'relayEvents.createMissingDone': '已建立 {count} 個接力，並已編好隊伍。',
  'relayEvents.createMissingNext': '開啟該接力的接力隊伍編排頁，即可在隊伍中加入或移除學生。',
  'relayEvents.createMissingFailed': '部分接力未能建立：',
  'relayEvents.createMissingOneFailed': '{name}：{reason}',
  'relayEvents.createMissingDivideFailed':
    '{name} 已建立，但未能編好隊伍：{reason} — 請在下方開啟該項目的編排頁，在該頁編隊。',

  'relayEvents.gridTitleHouse': '{type} — 社際接力',
  'relayEvents.createMissingCountHouse': '已有 {have} / {wanted} 個社際接力',
  'relayEvents.createMissingHouseHint':
    '社際接力按組別與分組組成，而學校於社際同樣設兩個距離：4x100M 及 4x400M，A 至 C 組各有男子及女子接力。下方每個方格會建立其所屬距離尚未有的項目 — 套用社際規則、設定該組別，並同時編好社隊，因此再按一次不會重複建立。',
  'relayEvents.createMissingHouseRule':
    '每個接力均以其所屬組別建立 — C 組接力即 C 組的接力 — 只沿用同組另一接力項目的項目類別，因此同一組的男子與女子接力屬同一項賽事。若某組別完全未有接力，會以 4x100M 建立，名稱由系統產生。',
  'relayEvents.loading': '載入接力項目…',
  'relayEvents.loadFailed': '無法載入接力項目',
  'relayEvents.listTitle': '本年度接力項目',
  'relayEvents.listHint':
    '尚未有隊伍的接力項目屬正常起始狀態，只是尚未分隊。讀取每個項目的編排頁，才知道它已有多少隊伍。',
  'relayEvents.eventCount': '{count} 個接力項目',
  'relayEvents.readingBoards': '正在讀取各項目的隊伍…',
  'relayEvents.boardFailed': '無法讀取此項目的接力隊伍',
  'relayEvents.boardFailedTitle': '無法讀取此項目的隊伍：',
  'relayEvents.countUnknown': '未能確定',
  'relayEvents.notReadyTitle': '此接力尚未齊隊，暫未能作賽：',
  'relayEvents.notReadyTeams':
    '現有 {count} 隊。接力至少需要 {needed} 隊，且每隊每一棒都要有跑手。',
  'relayEvents.rulesTitle': '接力的兩種分隊方式',
  'relayEvents.rulesHint':
    '分隊方式屬於項目本身而非本頁，因此下方按鈕會先設定項目自己的設定，然後才編隊。',
  'relayEvents.formRule':
    '每班一隊 — 依該接力所屬的級別，涵蓋該級別各年級的班別（1A、1B、1C、1D）；未設級別時，則為本項目所屬年級的每班一隊。',
  'relayEvents.houseRule':
    '該項目年級內每社一隊 — 例如 C Grade Yellow。',
  'relayEvents.kindIsStoredOnTheEvent':
    '兩種規則就是項目的接力類別，儲存在項目本身 — 與項目設定頁及該項目的編排頁所顯示的是同一個設定。',
  'relayEvents.adminClearHint':
    '以一組規則編隊後再改用另一組，原有隊伍會成為阻礙：伺服器在項目仍有隊伍時拒絕更改類別，並會說明原因。因此已有隊伍的項目須在其接力隊伍編排頁更改，先在該頁移除隊伍。',
  'relayEvents.teacherLimits':
    '教師可開啟任何接力項目的編排頁，安排自己任教班別的學生。建立接力項目、刪除接力項目、設定其類別及列印記錄表屬管理員權限，因此本頁不提供該等操作。',
  'relayEvents.teacherPrintLimit': '列印記錄表屬管理員權限，因此本頁不提供。',
  'relayEvents.anyForm': '只限本級',
  'relayEvents.formN': '{form} 年級',
  'relayEvents.makeFailed': '無法編排接力隊伍',
  'relayEvents.currentTeams': '此項目的隊伍',
  'relayEvents.printSheets': '列印記錄表',
  'relayEvents.printRun': '開啟列印頁',
  'relayEvents.printFailed': '無法列印記錄表',
  'relayEvents.deleteRelay': '刪除此接力項目',
  'relayEvents.deleteRelayConfirm':
    '確定刪除接力項目「{name}」？其 {count} 支隊伍、已安排的跑手及報名紀錄都會一併刪除。此操作無法復原。',
  'relayEvents.deleteRelayConfirmUnknown':
    '確定刪除接力項目「{name}」？其所有隊伍、已安排的跑手及報名紀錄都會一併刪除。此操作無法復原。',
  'teacher.relayTitle': '接力項目',
  'teacher.relayHint':
    '開啟接力項目即可看到其隊伍並加入跑手：每隊下方的名單會列出名冊上屬於該班別或社的學生，跑手可隨時移除並重新加入。接力項目分為班際（每班一隊）及社際（該級別每社一隊）；您可安排自己任教班別的學生，而項目的所有隊伍均會顯示，讓您看到各隊的組合。',
  'teacher.relayEmpty': '本年度暫時未有接力項目。',
  'teacher.relayAll': '查看所有接力項目',

  /* ---------------- backups ---------------- */
  'backups.title': '備份',
  'backups.subtitle': '伺服器上的所有賽季備份。每個備份保留該時刻的報名、分組、決賽名額、成績及學校紀錄。',
  'backups.loading': '載入備份…',
  'backups.loadFailed': '無法載入備份',
  'backups.none': '尚未有任何備份。重設賽季前會自動寫入一個備份。',
  'backups.count': '共 {count} 個備份',
  'backups.name': '檔案',
  'backups.size': '大小',
  'backups.taken': '建立時間',
  'backups.contents': '內容',
  'backups.contentsLine': '{enrollments} 項報名 · {groups} 組 · {results} 項成績 · {records} 項紀錄',
  'backups.unreadable': '此檔案無法讀取為賽季備份',
  'backups.download': '下載',
  'backups.downloading': '下載中…',
  'backups.downloadFailed': '無法下載備份',
  'backups.restore': '還原',
  'backups.restoring': '還原中…',
  'backups.restoreConfirm':
    '確定還原此備份？系統內所有報名、分組、決賽名額、已記錄成績及學校紀錄都會被刪除，並以 {name} 的內容取代。此操作無法復原。學生、項目及學年會保留。',
  'backups.restoreFailed': '備份並未還原',
  'backups.restoreResult':
    '已還原 {name} — 回復 {groups} 組、{enrollments} 項報名、{finalEntries} 個決賽名額、{results} 項成績及 {records} 項紀錄。',
  'backups.restoreSkipped': ' 檔案中有 {count} 列被略過：其學生或項目已不存在。',
  'backups.restoreOutcome': '還原結果',
  'backups.afterRestore': '賽季現時為該備份的內容。如被取代的狀態仍可能需要，請先另存新備份再作其他更改。',
  'backups.resetBackedUp':
    '賽季已重設。已先寫入備份：{file}（{bytes} 位元組）。已移除 {enrollments} 項報名、{groups} 組及 {results} 項成績。',
  'backups.latest': '最新備份',
  'backups.openBackups': '所有備份',
  'admin.backups': '備份',
  'admin.backupsHint': '查看每個備份的內容、下載或還原。',
  'nav.backups': '備份',
};

const messages: Record<Lang, Record<keyof typeof en, string>> = { en, zh };

export type MessageKey = keyof typeof en;

function interpolate(template: string, vars?: Record<string, string | number>): string {
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (match, name) =>
    vars[name] === undefined ? match : String(vars[name]),
  );
}

interface I18nValue {
  lang: Lang;
  setLang: (lang: Lang) => void;
  /** Translate a key, optionally filling `{placeholders}`. */
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
  /** Localised label for a domain enum, falling back to the raw value. */
  label: (prefix: 'category' | 'sex' | 'grade' | 'grade.short' | 'sheet' | 'unit' | 'role',
          value: string | null | undefined) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

export function LanguageProvider({ children }: { children: React.ReactNode }) {
  const [lang, setLangState] = useState<Lang>('en');

  // Restore the saved choice once we are in the browser.
  useEffect(() => {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored === 'en' || stored === 'zh') {
      setLangState(stored);
    }
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang === 'zh' ? 'zh-Hant-HK' : 'en';
  }, [lang]);

  const setLang = useCallback((next: Lang) => {
    setLangState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private browsing: the choice simply will not persist.
    }
  }, []);

  const t = useCallback(
    (key: MessageKey, vars?: Record<string, string | number>) =>
      interpolate(messages[lang][key] ?? messages.en[key] ?? key, vars),
    [lang],
  );

  const label = useCallback<I18nValue['label']>(
    (prefix, value) => {
      if (value === null || value === undefined || value === '') return '';
      const key = `${prefix}.${value}` as MessageKey;
      return messages[lang][key] ?? messages.en[key] ?? value;
    },
    [lang],
  );

  const value = useMemo(() => ({ lang, setLang, t, label }), [lang, setLang, t, label]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const context = useContext(I18nContext);
  if (!context) {
    throw new Error('useI18n must be used inside <LanguageProvider>');
  }
  return context;
}
