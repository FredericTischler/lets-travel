import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../i18n/translate.service';
import { RatingComponent } from './rating.component';

describe('RatingComponent', () => {
  function render(value: number | null): HTMLElement {
    const fixture = TestBed.createComponent(RatingComponent);
    fixture.componentRef.setInput('value', value);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [RatingComponent] }));

  it('shows filled stars and the textual value (the stars are decorative)', () => {
    const el = render(4);

    expect(el.querySelector('[aria-hidden="true"]')!.textContent).toBe('★★★★☆');
    expect(el.querySelector('[data-testid="rating-text"]')!.textContent).toBe('4 sur 5');
  });

  it('formats a mean with a decimal comma and rounds the stars', () => {
    const el = render(4.3);

    expect(el.querySelector('[data-testid="rating-text"]')!.textContent).toBe('4,3 sur 5');
    expect(el.querySelector('[aria-hidden="true"]')!.textContent).toBe('★★★★☆');
  });

  it('shows a dash, not five empty stars, when there is no rating yet', () => {
    const el = render(null);

    expect(el.textContent?.trim()).toBe('—');
    expect(el.querySelector('[data-testid="rating-text"]')).toBeNull();
  });

  it('reads "out of 5" with a decimal point in English', () => {
    TestBed.inject(TranslateService).setLocale('en');
    const el = render(4.3);

    expect(el.querySelector('[data-testid="rating-text"]')!.textContent).toBe('4.3 out of 5');
  });
});

// The locale is persisted in localStorage: never leak 'en' into the next spec.
afterEach(() => localStorage.removeItem('admin-dashboard.locale'));
