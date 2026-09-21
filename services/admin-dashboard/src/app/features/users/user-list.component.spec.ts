import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { User } from './user.service';
import { UserListComponent } from './user-list.component';

describe('UserListComponent', () => {
  let fixture: ComponentFixture<UserListComponent>;
  let component: UserListComponent;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.identityApiUrl}/users`;

  const sampleUser: User = {
    id: 'user-1',
    email: 'admin@example.com',
    role: 'ADMIN',
    createdAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [UserListComponent, HttpClientTestingModule],
    }).compileComponents();

    fixture = TestBed.createComponent(UserListComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('loads and renders the user list on init', () => {
    fixture.detectChanges(); // triggers ngOnInit -> list()

    httpMock.expectOne(baseUrl).flush([sampleUser]);
    fixture.detectChanges();

    expect(component['users']()).toEqual([sampleUser]);
    expect(component['loading']()).toBe(false);

    const cells = fixture.nativeElement.querySelectorAll('td.table-cell');
    const text = Array.from(cells as NodeListOf<HTMLElement>).map((cell) => cell.textContent?.trim());
    expect(text).toContain('admin@example.com');
  });

  it('shows an error message when loading the list fails', () => {
    fixture.detectChanges();

    httpMock.expectOne(baseUrl).flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component['error']()).toBe('Impossible de charger la liste des utilisateurs.');
  });

  it('createUser() submits the form and reloads the list on success', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);
    fixture.detectChanges();

    component['createEmail'] = 'new@example.com';
    component['createPassword'] = 'secret';
    component.createUser();

    const createReq = httpMock.expectOne(baseUrl);
    expect(createReq.request.method).toBe('POST');
    // No role picked: TRAVELER (least privilege), always sent explicitly.
    expect(createReq.request.body).toEqual({
      email: 'new@example.com',
      password: 'secret',
      role: 'TRAVELER',
    });
    createReq.flush(sampleUser);

    // createUser() reloads the list after success.
    httpMock.expectOne(baseUrl).flush([sampleUser]);

    expect(component['createEmail']).toBe('');
    expect(component['createPassword']).toBe('');
  });

  it.each(['ADMIN', 'TRAVEL_MANAGER'] as const)('createUser() sends the role chosen in the selector (%s)', (role) => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);

    component['createEmail'] = 'new@example.com';
    component['createPassword'] = 'secret-password';
    component['createRole'] = role;
    component.createUser();

    const createReq = httpMock.expectOne(baseUrl);
    expect(createReq.request.body.role).toBe(role);
    createReq.flush(sampleUser);
    httpMock.expectOne(baseUrl).flush([sampleUser]);

    // The selector goes back to the safest choice after a creation.
    expect(component['createRole']).toBe('TRAVELER');
  });

  it('offers the three roles in the create form, ADMIN included', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);
    fixture.detectChanges();

    const options = Array.from(
      fixture.nativeElement.querySelectorAll('#createRole option') as NodeListOf<HTMLOptionElement>,
    ).map((o) => o.textContent?.trim());
    expect(options).toEqual([
      'Administrateur (ADMIN)',
      'Organisateur (TRAVEL_MANAGER)',
      'Voyageur (TRAVELER)',
    ]);
  });

  it('createUser() surfaces a backend error message without clearing the form', () => {
    fixture.detectChanges();
    httpMock.expectOne(baseUrl).flush([]);

    component['createEmail'] = 'new@example.com';
    component['createPassword'] = 'secret';
    component.createUser();

    const createReq = httpMock.expectOne(baseUrl);
    createReq.flush({ error: 'Email already used' }, { status: 409, statusText: 'Conflict' });

    expect(component['createError']()).toBe('Email already used');
    expect(component['createEmail']).toBe('new@example.com');
  });
});
