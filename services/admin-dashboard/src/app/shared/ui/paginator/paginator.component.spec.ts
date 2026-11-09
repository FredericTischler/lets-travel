import { TestBed } from '@angular/core/testing';

import { TranslateService } from '../../i18n/translate.service';
import { PaginatorComponent } from './paginator.component';

describe('PaginatorComponent', () => {
  function render(): HTMLElement {
    const fixture = TestBed.createComponent(PaginatorComponent);
    fixture.componentRef.setInput('page', 1);
    fixture.componentRef.setInput('totalPages', 5);
    fixture.componentRef.setInput('totalElements', 42);
    fixture.detectChanges();
    return fixture.nativeElement;
  }

  beforeEach(() => TestBed.configureTestingModule({ imports: [PaginatorComponent] }));
  afterEach(() => localStorage.removeItem('admin-dashboard.locale'));

  it('summarises the position in French by default', () => {
    const el = render();

    expect(el.querySelector('[data-testid="paginator-summary"]')!.textContent!.trim()).toBe(
      'Page 2 sur 5 (42 au total)',
    );
    expect(el.textContent).toContain('Précédent');
    expect(el.textContent).toContain('Suivant');
  });

  it('summarises the position in English when the locale is switched', () => {
    TestBed.inject(TranslateService).setLocale('en');
    const el = render();

    expect(el.querySelector('[data-testid="paginator-summary"]')!.textContent!.trim()).toBe(
      'Page 2 of 5 (42 in total)',
    );
    expect(el.textContent).toContain('Previous');
    expect(el.textContent).toContain('Next');
  });
});
