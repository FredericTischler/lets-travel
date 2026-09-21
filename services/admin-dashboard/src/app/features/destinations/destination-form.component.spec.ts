import { ComponentFixture, TestBed } from '@angular/core/testing';

import { DestinationFormComponent } from './destination-form.component';
import { Destination, DestinationCreateInput } from './destination.service';

describe('DestinationFormComponent', () => {
  let fixture: ComponentFixture<DestinationFormComponent>;
  let component: DestinationFormComponent;
  let submitted: DestinationCreateInput[];
  let cancelled: number;

  const MANAGER_ID = '11111111-1111-4111-8111-111111111111';

  const existing: Destination = {
    id: 'dest-1',
    name: 'Lisbon',
    country: 'Portugal',
    startDate: '2027-01-10',
    endDate: '2027-01-20',
    durationDays: 11,
    managerId: MANAGER_ID,
    price: 1200,
    capacity: 20,
    activities: [{ id: 'a1', name: 'Tram 28 ride' }],
    accommodations: [
      { id: 'h1', name: 'Hotel Lisboa', type: 'HOTEL', checkIn: '2027-01-10', checkOut: null },
    ],
    createdAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [DestinationFormComponent] }).compileComponents();

    fixture = TestBed.createComponent(DestinationFormComponent);
    component = fixture.componentInstance;
    submitted = [];
    cancelled = 0;
    component.submitted.subscribe((value) => submitted.push(value));
    component.cancelled.subscribe(() => cancelled++);
  });

  function form() {
    return component['form'];
  }

  function fillValid(extra: Record<string, unknown> = {}) {
    form().patchValue({
      name: '  Porto ',
      country: 'Portugal',
      startDate: '2027-03-01',
      endDate: '2027-03-05',
      price: 450,
      capacity: 12,
      ...extra,
    });
  }

  function labels(): string[] {
    return Array.from(fixture.nativeElement.querySelectorAll('label') as NodeListOf<HTMLElement>).map(
      (label) => label.textContent?.trim() ?? '',
    );
  }

  it('does not emit and marks fields as touched when the form is invalid', () => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();

    component.submit();

    expect(submitted).toEqual([]);
    expect(form().controls.name.touched).toBe(true);
  });

  it('for a Travel Manager: hides the manager field and always sends the fixed manager id', () => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();
    fillValid();
    fixture.detectChanges();

    expect(labels()).not.toContain("Identifiant de l'organisateur");
    component.addActivity();
    component['activities'].at(0).setValue(' Fado night ');
    component.addAccommodation();
    component['accommodations'].at(0).setValue({ name: 'Hotel Porto', type: 'HOTEL', checkIn: '', checkOut: '' });

    component.submit();

    expect(submitted).toEqual([
      {
        name: 'Porto',
        country: 'Portugal',
        startDate: '2027-03-01',
        endDate: '2027-03-05',
        price: 450,
        capacity: 12,
        managerId: MANAGER_ID,
        activities: ['Fado night'],
        accommodations: [{ name: 'Hotel Porto', type: 'HOTEL', checkIn: null, checkOut: null }],
      },
    ]);
  });

  it('for an admin: shows a required manager-id field that must be a UUID', () => {
    fixture.detectChanges();
    fillValid();
    fixture.detectChanges();
    expect(labels()).toContain("Identifiant de l'organisateur");

    component.submit(); // managerId still empty
    expect(submitted).toEqual([]);

    form().patchValue({ managerId: 'not-a-uuid' });
    component.submit();
    expect(submitted).toEqual([]);

    form().patchValue({ managerId: MANAGER_ID });
    component.submit();
    expect(submitted).toHaveLength(1);
    expect(submitted[0].managerId).toBe(MANAGER_ID);
  });

  it('rejects an end date before the start date', () => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();
    fillValid({ startDate: '2027-03-10', endDate: '2027-03-01' });
    form().controls.endDate.markAsTouched();
    fixture.detectChanges();

    component.submit();

    expect(submitted).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('La date de fin doit être postérieure');
  });

  it.each([
    ['a negative price', { price: -1 }],
    ['a missing price', { price: null }],
    ['a zero capacity', { capacity: 0 }],
    ['a fractional capacity', { capacity: 2.5 }],
  ])('rejects %s', (_label, invalid) => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();
    fillValid(invalid);

    component.submit();

    expect(submitted).toEqual([]);
  });

  it('accepts a free travel (price 0)', () => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();
    fillValid({ price: 0 });

    component.submit();

    expect(submitted).toHaveLength(1);
    expect(submitted[0].price).toBe(0);
  });

  it('in edit mode: loads the destination, hides the manager field and keeps the existing manager', () => {
    fixture.componentRef.setInput('destination', existing);
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Modifier la destination');
    expect(labels()).not.toContain("Identifiant de l'organisateur");
    expect(form().controls.name.value).toBe('Lisbon');
    expect(form().controls.price.value).toBe(1200);
    expect(component['activities'].length).toBe(1);
    expect(component['accommodations'].length).toBe(1);

    form().patchValue({ endDate: '2027-01-25' });
    component.submit();

    expect(submitted).toHaveLength(1);
    expect(submitted[0]).toMatchObject({
      name: 'Lisbon',
      endDate: '2027-01-25',
      managerId: MANAGER_ID,
      activities: ['Tram 28 ride'],
      accommodations: [{ name: 'Hotel Lisboa', type: 'HOTEL', checkIn: '2027-01-10', checkOut: null }],
    });
  });

  it('emits `cancelled` from the cancel button, only shown while editing', () => {
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('Annuler');

    fixture.componentRef.setInput('destination', existing);
    fixture.detectChanges();
    const cancel = Array.from(fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>).find(
      (b) => b.textContent?.trim() === 'Annuler',
    );
    cancel!.click();

    expect(cancelled).toBe(1);
  });

  it('returns to create mode and empties the form when the edited destination is cleared', () => {
    fixture.componentRef.setInput('destination', existing);
    fixture.detectChanges();

    fixture.componentRef.setInput('destination', null);
    fixture.detectChanges();

    expect(form().controls.name.value).toBe('');
    expect(component['activities'].length).toBe(0);
    expect(fixture.nativeElement.textContent).toContain('Créer une destination');
  });

  it('reset() empties the form but keeps the fixed manager id', () => {
    fixture.componentRef.setInput('fixedManagerId', MANAGER_ID);
    fixture.detectChanges();
    fillValid();

    component.reset();

    expect(form().controls.name.value).toBe('');
    expect(form().controls.managerId.value).toBe(MANAGER_ID);
  });

  it('shows the error and disables the submit button while saving', () => {
    fixture.componentRef.setInput('error', 'Impossible de créer cette destination.');
    fixture.componentRef.setInput('saving', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'Impossible de créer cette destination.',
    );
    const submit = fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });
});
