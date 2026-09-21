import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Report } from './report.service';
import { ReportQueueComponent } from './report-queue.component';

describe('ReportQueueComponent', () => {
  let fixture: ComponentFixture<ReportQueueComponent>;
  let httpMock: HttpTestingController;

  const reportsUrl = `${environment.identityApiUrl}/reports`;
  const usersUrl = `${environment.identityApiUrl}/users`;

  const open: Report = {
    id: 'r-open',
    reporterId: 'u-reporter',
    reportedUserId: 'u-manager',
    reason: '<img src=x onerror=alert(1)> rude',
    status: 'OPEN',
    createdAt: '2026-09-02T10:00:00Z',
  };
  const dismissed: Report = {
    id: 'r-done',
    reporterId: 'u-reporter',
    reportedUserId: 'u-manager',
    reason: 'old one',
    status: 'DISMISSED',
    createdAt: '2026-09-03T10:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReportQueueComponent, HttpClientTestingModule],
    }).compileComponents();

    fixture = TestBed.createComponent(ReportQueueComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(reports: Report[], users: unknown = [
    { id: 'u-reporter', email: 'traveler@example.com', role: 'TRAVELER', createdAt: '' },
    { id: 'u-manager', email: 'manager@example.com', role: 'TRAVEL_MANAGER', createdAt: '' },
  ]) {
    fixture.detectChanges();
    httpMock.expectOne(reportsUrl).flush(reports);
    const usersReq = httpMock.expectOne(usersUrl);
    if (Array.isArray(users)) {
      usersReq.flush(users);
    } else {
      usersReq.flush('forbidden', { status: 403, statusText: 'Forbidden' });
    }
    fixture.detectChanges();
  }

  function rowTexts(): string[] {
    const rows = fixture.nativeElement.querySelectorAll('tbody tr.table-row') as NodeListOf<HTMLElement>;
    return Array.from(rows).map((row) => row.textContent ?? '');
  }

  function actionButtons(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('tbody button')) as HTMLButtonElement[];
  }

  it('lists reports with resolved emails, the open one first', () => {
    load([dismissed, open]);

    const rows = rowTexts();
    expect(rows).toHaveLength(2);
    expect(rows[0]).toContain('Ouvert');
    expect(rows[0]).toContain('traveler@example.com');
    expect(rows[0]).toContain('manager@example.com');
    expect(rows[1]).toContain('Rejeté');
    expect(fixture.nativeElement.querySelector('[data-testid="open-count"]').textContent).toContain('1 ');
  });

  it('renders the free-text reason as text, never as HTML', () => {
    load([open]);

    const cell = fixture.nativeElement.querySelector('tbody td.whitespace-pre-wrap') as HTMLElement;
    expect(cell.textContent).toContain('<img src=x onerror=alert(1)> rude');
    expect(fixture.nativeElement.querySelector('tbody img')).toBeNull();
  });

  it('falls back to raw ids when the users list is unavailable', () => {
    load([open], 'forbidden');

    expect(rowTexts()[0]).toContain('u-reporter');
    expect(rowTexts()[0]).toContain('u-manager');
  });

  it('offers the three decisions only on OPEN reports', () => {
    load([open, dismissed]);

    expect(actionButtons().map((b) => b.textContent?.trim())).toEqual([
      'Marquer examiné',
      'Rejeter',
      'Sanctionner',
    ]);
  });

  it('PATCHes the chosen status and updates the row in place', () => {
    load([open]);

    actionButtons()[2].click(); // Sanctionner
    const req = httpMock.expectOne(`${reportsUrl}/r-open/status`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ status: 'ACTIONED' });
    req.flush({ ...open, status: 'ACTIONED' });
    fixture.detectChanges();

    expect(rowTexts()[0]).toContain('Sanctionné');
    expect(actionButtons()).toHaveLength(0);
  });

  it('reloads the queue and explains when the report was already decided (409)', () => {
    load([open]);

    actionButtons()[0].click();
    httpMock
      .expectOne(`${reportsUrl}/r-open/status`)
      .flush({ error: 'already terminal', status: 409 }, { status: 409, statusText: 'Conflict' });
    // The 409 triggers a refresh of both lists.
    httpMock.expectOne(reportsUrl).flush([{ ...open, status: 'REVIEWED' }]);
    httpMock.expectOne(usersUrl).flush([]);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('déjà été traité');
    expect(rowTexts()[0]).toContain('Examiné');
  });

  it('filters by status', () => {
    load([open, dismissed]);

    const chips = Array.from(fixture.nativeElement.querySelectorAll('button[aria-pressed]')) as HTMLButtonElement[];
    chips.find((chip) => chip.textContent?.includes('Rejeté'))!.click();
    fixture.detectChanges();

    const rows = rowTexts();
    expect(rows).toHaveLength(1);
    expect(rows[0]).toContain('old one');
  });

  it('shows an error when the queue cannot be loaded', () => {
    fixture.detectChanges();
    // Users first: forkJoin cancels the sibling request as soon as one fails.
    httpMock.expectOne(usersUrl).flush([]);
    httpMock.expectOne(reportsUrl).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de charger les signalements.',
    );
  });
});
