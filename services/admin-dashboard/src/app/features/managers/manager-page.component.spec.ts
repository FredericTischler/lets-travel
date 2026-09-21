import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { ManagerStats } from '../stats/stats.service';
import { ManagerPageComponent } from './manager-page.component';

describe('ManagerPageComponent', () => {
  let fixture: ComponentFixture<ManagerPageComponent>;
  let component: ManagerPageComponent;
  let httpMock: HttpTestingController;
  const currentUser = { value: 'traveler-1' };

  const statsUrl = `${environment.travelApiUrl}/managers/manager-1/stats`;
  const reportsUrl = `${environment.identityApiUrl}/reports`;

  const stats: ManagerStats = {
    managerId: 'manager-1',
    activeTravels: 4,
    pastTravels: 2,
    subscribers: 17,
    feedbackCount: 6,
    averageRating: 4.5,
    pastRatings: [
      { destinationId: 'd1', name: 'Lisbon', country: 'Portugal', startDate: '2026-05-01', endDate: '2026-05-08', feedbackCount: 4, averageRating: 4.75 },
      { destinationId: 'd2', name: 'Alps', country: 'Suisse', startDate: '2026-06-01', endDate: '2026-06-08', feedbackCount: 0, averageRating: null },
    ],
  };

  beforeEach(async () => {
    currentUser.value = 'traveler-1';
    await TestBed.configureTestingModule({
      imports: [ManagerPageComponent, HttpClientTestingModule],
      providers: [provideRouter([]), { provide: AuthService, useValue: { getCurrentUserId: () => currentUser.value } }],
    }).compileComponents();
    fixture = TestBed.createComponent(ManagerPageComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('id', 'manager-1');
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(s: ManagerStats = stats, reports: number | 'error' = 3) {
    fixture.detectChanges();
    httpMock.expectOne(statsUrl).flush(s);
    const count = httpMock.expectOne(`${reportsUrl}/count/manager-1`);
    reports === 'error' ? count.flush('x', { status: 500, statusText: 'E' }) : count.flush({ count: reports });
    fixture.detectChanges();
  }

  const el = () => fixture.nativeElement as HTMLElement;
  const tile = (id: string) => el().querySelector(`[data-testid="${id}"]`)!.textContent!.replace(/\s+/g, ' ').trim();

  it('shows the statistics, the past ratings and the number of reports', () => {
    load();

    expect(tile('kpi-travels')).toContain('4');
    expect(tile('kpi-past')).toContain('2');
    expect(tile('kpi-subscribers')).toContain('17');
    expect(tile('kpi-rating')).toContain('4,5 sur 5');
    expect(tile('kpi-rating')).toContain('6 avis');
    expect(tile('kpi-reports')).toContain('3');

    const rows = el().querySelectorAll('[data-testid="past-rating"]');
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain('4,75 sur 5');
    // No feedback yet: a dash, not five empty stars.
    expect(rows[1].textContent).toContain('—');
  });

  it('shows a dash when the report count cannot be read', () => {
    load(stats, 'error');

    expect(tile('kpi-reports')).toContain('—');
  });

  it('says so when the manager has no travel and no feedback (the backend answers zeros for an unknown id)', () => {
    load({ ...stats, activeTravels: 0, pastTravels: 0, subscribers: 0, feedbackCount: 0, averageRating: null, pastRatings: [] });

    expect(el().querySelector('[data-testid="no-activity"]')).not.toBeNull();
    expect(tile('kpi-rating')).toContain('—');
  });

  it('reuses the report flow: POSTs the manager id and the trimmed reason, then refreshes the count', () => {
    load();

    component['reportReason'] = '  Comportement <b>déplacé</b>  ';
    component.submitReport();
    const req = httpMock.expectOne(reportsUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reportedUserId: 'manager-1', reason: 'Comportement <b>déplacé</b>' });
    req.flush({ id: 'r1' });
    httpMock.expectOne(`${reportsUrl}/count/manager-1`).flush({ count: 4 });
    fixture.detectChanges();

    expect(el().textContent).toContain('Votre signalement a été transmis');
    expect(tile('kpi-reports')).toContain('4');
  });

  it('does not send an empty reason and shows the backend refusal', () => {
    load();

    component['reportReason'] = '   ';
    component.submitReport();
    httpMock.expectNone(reportsUrl);

    component['reportReason'] = 'motif';
    component.submitReport();
    httpMock.expectOne(reportsUrl).flush({ error: 'User not found' }, { status: 404, statusText: 'Not Found' });
    expect(component['reportError']()).toBe('User not found');
  });

  it('does not offer to report yourself', () => {
    currentUser.value = 'manager-1';
    load();

    expect(el().textContent).toContain('Vous ne pouvez pas vous signaler vous-même.');
    expect(Array.from(el().querySelectorAll('button')).some((b) => b.textContent?.includes("Signaler l'organisateur"))).toBe(false);
  });

  it('shows an error when the page cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(statsUrl).flush('boom', { status: 500, statusText: 'E' });
    httpMock.expectOne(`${reportsUrl}/count/manager-1`).flush({ count: 0 });
    fixture.detectChanges();

    expect(el().querySelector('[role="alert"]')!.textContent).toContain('Impossible de charger cette page organisateur.');
  });

  it('escapes travel names instead of interpreting them as HTML', () => {
    load({ ...stats, pastRatings: [{ ...stats.pastRatings[0], name: '<img src=x onerror=alert(1)>' }] });

    expect(el().querySelector('img')).toBeNull();
    expect(el().textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
