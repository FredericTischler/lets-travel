import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { AuthService } from '../../core/auth/auth.service';
import { Destination, DestinationCreateInput } from '../destinations/destination.service';
import { MyTravelsComponent } from './my-travels.component';

describe('MyTravelsComponent', () => {
  let fixture: ComponentFixture<MyTravelsComponent>;
  let component: MyTravelsComponent;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/destinations`;
  const ME = '11111111-1111-4111-8111-111111111111';
  const OTHER = '22222222-2222-4222-8222-222222222222';

  function destination(id: string, name: string, managerId: string | null): Destination {
    return {
      id,
      name,
      country: 'Portugal',
      startDate: '2027-01-10',
      endDate: '2027-01-20',
      durationDays: 11,
      managerId,
      price: 900,
      capacity: 15,
      activities: [],
      accommodations: [],
      createdAt: '2026-01-01T00:00:00Z',
    };
  }

  const input: DestinationCreateInput = {
    name: 'Porto',
    country: 'Portugal',
    startDate: '2027-03-01',
    endDate: '2027-03-05',
    price: 450,
    capacity: 12,
    managerId: ME,
    activities: [],
    accommodations: [],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [MyTravelsComponent, HttpClientTestingModule],
      providers: [provideRouter([]), { provide: AuthService, useValue: { getCurrentUserId: () => ME } }],
    }).compileComponents();

    fixture = TestBed.createComponent(MyTravelsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    vi.stubGlobal('confirm', vi.fn(() => true));
  });

  afterEach(() => {
    httpMock.verify();
    vi.unstubAllGlobals();
  });

  function load() {
    fixture.detectChanges();
    httpMock.expectOne(url).flush([
      destination('mine-1', 'Mine One', ME),
      destination('theirs', 'Not Mine', OTHER),
      destination('legacy', 'No Manager', null),
      destination('mine-2', 'Mine Two', ME),
    ]);
    fixture.detectChanges();
  }

  function rows(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('tbody tr.table-row td:first-child') as NodeListOf<HTMLElement>).map(
      (td) => td.textContent?.trim() ?? '',
    );
  }

  it("lists only the travels whose manager is the caller", () => {
    load();

    expect(rows()).toEqual(['Mine One', 'Mine Two']);
  });

  it('links each travel to its subscriber list', () => {
    load();

    const link = fixture.nativeElement.querySelector('tbody a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/manager/travels/mine-1/subscribers');
  });

  it("creates a travel in the caller's own name, then reloads", () => {
    load();

    component['save'](input);
    const create = httpMock.expectOne(url);
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(input);
    create.flush(destination('new', 'Porto', ME));
    httpMock.expectOne(url).flush([destination('new', 'Porto', ME)]);
    fixture.detectChanges();

    expect(rows()).toEqual(['Porto']);
    expect(component['saving']()).toBe(false);
  });

  it('edits with a PUT that carries no managerId', () => {
    load();
    component['startEdit'](destination('mine-1', 'Mine One', ME));

    component['save']({ ...input, name: 'Renamed' });
    const update = httpMock.expectOne(`${url}/mine-1`);
    expect(update.request.method).toBe('PUT');
    expect(update.request.body).not.toHaveProperty('managerId');
    expect(update.request.body).toMatchObject({ name: 'Renamed', price: 450, capacity: 12 });
    update.flush(destination('mine-1', 'Renamed', ME));
    httpMock.expectOne(url).flush([]);

    expect(component['editing']()).toBeNull();
  });

  it('surfaces a backend refusal without leaving edit mode', () => {
    load();
    component['startEdit'](destination('mine-1', 'Mine One', ME));

    component['save'](input);
    httpMock
      .expectOne(`${url}/mine-1`)
      .flush({ error: 'Not allowed to manage another manager\'s travel' }, { status: 403, statusText: 'Forbidden' });

    expect(component['saveError']()).toBe("Not allowed to manage another manager's travel");
    expect(component['editing']()).not.toBeNull();
  });

  it('deletes after confirmation and reloads', () => {
    load();

    component['remove'](destination('mine-1', 'Mine One', ME));
    const del = httpMock.expectOne(`${url}/mine-1`);
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
    httpMock.expectOne(url).flush([destination('mine-2', 'Mine Two', ME)]);
    fixture.detectChanges();

    expect(rows()).toEqual(['Mine Two']);
  });

  it('does not delete when the confirmation is declined', () => {
    vi.stubGlobal('confirm', vi.fn(() => false));
    load();

    component['remove'](destination('mine-1', 'Mine One', ME));

    httpMock.expectNone(`${url}/mine-1`);
  });

  it('shows an error when the list cannot be loaded', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component['error']()).toBe('Impossible de charger vos voyages.');
  });
});
