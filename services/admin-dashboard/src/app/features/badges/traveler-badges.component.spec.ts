import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { TravelerBadgesComponent } from './traveler-badges.component';
import { TravelerBadges } from './badge.service';

describe('TravelerBadgesComponent', () => {
  let fixture: ComponentFixture<TravelerBadgesComponent>;
  let httpMock: HttpTestingController;

  const url = `${environment.travelApiUrl}/travelers/me/badges`;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TravelerBadgesComponent, HttpClientTestingModule],
    }).compileComponents();
    fixture = TestBed.createComponent(TravelerBadgesComponent);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function load(data: TravelerBadges) {
    fixture.detectChanges();
    httpMock.expectOne(url).flush(data);
    fixture.detectChanges();
  }

  it('shows an earned badge without a progress count', () => {
    load({
      destinationsVisited: 3,
      countriesVisited: 3,
      reviewsGiven: 0,
      badges: [
        { code: 'EXPLORER', threshold: 1, progress: 1, earned: true },
        { code: 'GLOBETROTTER', threshold: 5, progress: 3, earned: false },
        { code: 'CRITIC', threshold: 5, progress: 0, earned: false },
      ],
    });

    const items = Array.from(fixture.nativeElement.querySelectorAll('[data-testid="badge"]')) as HTMLElement[];
    expect(items).toHaveLength(3);
    const explorer = items.find((i) => i.textContent?.includes('Explorateur'))!;
    expect(explorer.querySelector('[data-testid="badge-progress"]')).toBeNull();
  });

  it('shows a progress count for an unearned badge', () => {
    load({
      destinationsVisited: 0,
      countriesVisited: 0,
      reviewsGiven: 0,
      badges: [{ code: 'EXPLORER', threshold: 1, progress: 0, earned: false }],
    });

    const item = fixture.nativeElement.querySelector('[data-testid="badge"]') as HTMLElement;
    expect(item.querySelector('[data-testid="badge-progress"]')!.textContent).toContain('0/1');
  });

  it('hides itself entirely when the call fails, without breaking the page', () => {
    fixture.detectChanges();
    httpMock.expectOne(url).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="traveler-badges"]')).toBeNull();
  });
});
