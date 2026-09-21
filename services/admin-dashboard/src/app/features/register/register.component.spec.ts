import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { RegisterComponent } from './register.component';

/** A syntactically valid unsigned JWT carrying the given claims. */
function buildToken(payload: Record<string, unknown>): string {
  const b64 = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

describe('RegisterComponent', () => {
  let fixture: ComponentFixture<RegisterComponent>;
  let component: RegisterComponent;
  let httpMock: HttpTestingController;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  const usersUrl = `${environment.identityApiUrl}/users`;
  const loginUrl = `${environment.identityApiUrl}/login`;

  beforeEach(async () => {
    localStorage.removeItem('admin-dashboard.jwt');
    await TestBed.configureTestingModule({
      imports: [RegisterComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(RegisterComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('admin-dashboard.jwt');
  });

  function fill(email: string, password: string, role: 'TRAVELER' | 'TRAVEL_MANAGER' = 'TRAVELER') {
    component['email'] = email;
    component['password'] = password;
    component['role'] = role;
  }

  it('offers TRAVELER and TRAVEL_MANAGER, never ADMIN', () => {
    const radios = Array.from(fixture.nativeElement.querySelectorAll('input[type="radio"]') as NodeListOf<HTMLInputElement>);

    expect(radios.map((r) => r.value)).toEqual(['TRAVELER', 'TRAVEL_MANAGER']);
    expect(fixture.nativeElement.textContent).not.toContain('Administrateur');
  });

  it('links back to the login page', () => {
    const link = fixture.nativeElement.querySelector('a[href="/login"]');

    expect(link).not.toBeNull();
  });

  it('rejects a too short password without calling the backend', () => {
    fill('a@example.com', 'short');

    component.submit();

    httpMock.expectNone(usersUrl);
    expect(component['error']()).toContain('au moins 8 caractères');
  });

  it('registers with the chosen role, logs in and lands on the home page of that role', () => {
    fill('boss@example.com', 'longenough', 'TRAVEL_MANAGER');

    component.submit();
    const register = httpMock.expectOne(usersUrl);
    expect(register.request.method).toBe('POST');
    expect(register.request.body).toEqual({
      email: 'boss@example.com',
      password: 'longenough',
      role: 'TRAVEL_MANAGER',
    });
    register.flush({ id: 'u1', email: 'boss@example.com', role: 'TRAVEL_MANAGER', createdAt: '' });

    const login = httpMock.expectOne(loginUrl);
    expect(login.request.body).toEqual({ email: 'boss@example.com', password: 'longenough' });
    login.flush({
      id: 'u1',
      email: 'boss@example.com',
      token: buildToken({ sub: 'u1', role: 'TRAVEL_MANAGER' }),
    });

    expect(navigateByUrl).toHaveBeenCalledWith('/manager/travels');
    expect(component['submitting']()).toBe(false);
  });

  it('sends a new traveler to the catalogue', () => {
    fill('t@example.com', 'longenough', 'TRAVELER');

    component.submit();
    httpMock.expectOne(usersUrl).flush({ id: 'u2', email: 't@example.com', role: 'TRAVELER', createdAt: '' });
    httpMock.expectOne(loginUrl).flush({ id: 'u2', email: 't@example.com', token: buildToken({ sub: 'u2', role: 'TRAVELER' }) });

    expect(navigateByUrl).toHaveBeenCalledWith('/travels');
  });

  it('shows the backend message when the email is already used, without trying to log in', () => {
    fill('taken@example.com', 'longenough');

    component.submit();
    httpMock
      .expectOne(usersUrl)
      .flush({ error: 'Email already in use' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    httpMock.expectNone(loginUrl);
    expect(component['error']()).toBe('Email already in use');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Email already in use');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });

  it('never sends a role the form does not offer', () => {
    component['role'] = 'ADMIN' as never;
    component['email'] = 'a@example.com';
    component['password'] = 'longenough';

    component.submit();

    httpMock.expectNone(usersUrl);
    expect(component['error']()).toBe('Rôle invalide.');
  });
});
