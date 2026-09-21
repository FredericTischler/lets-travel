import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { environment } from '../../../environments/environment';
import { LoginComponent } from './login.component';

function buildToken(payload: Record<string, unknown>): string {
  const b64 = (obj: unknown) =>
    btoa(JSON.stringify(obj)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'none' })}.${b64(payload)}.sig`;
}

describe('LoginComponent', () => {
  let fixture: ComponentFixture<LoginComponent>;
  let component: LoginComponent;
  let httpMock: HttpTestingController;
  let navigateByUrl: ReturnType<typeof vi.spyOn>;

  const loginUrl = `${environment.identityApiUrl}/login`;

  beforeEach(async () => {
    localStorage.removeItem('admin-dashboard.jwt');
    await TestBed.configureTestingModule({
      imports: [LoginComponent, HttpClientTestingModule],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(LoginComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
    navigateByUrl = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fixture.detectChanges();
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.removeItem('admin-dashboard.jwt');
  });

  function logIn(role: string | undefined) {
    component['email'] = 'user@example.com';
    component['password'] = 'secret';
    component.submit();
    httpMock.expectOne(loginUrl).flush({
      id: 'u1',
      email: 'user@example.com',
      token: buildToken(role ? { sub: 'u1', role } : { sub: 'u1' }),
    });
  }

  it('links to the sign-up page', () => {
    expect(fixture.nativeElement.querySelector('a[href="/register"]')).not.toBeNull();
  });

  it.each([
    ['ADMIN', '/users'],
    ['TRAVEL_MANAGER', '/manager/travels'],
    ['TRAVELER', '/travels'],
  ])('sends a %s to %s after login', (role, expected) => {
    logIn(role);

    expect(navigateByUrl).toHaveBeenCalledWith(expected);
  });

  it('falls back to the root (and so the guards) for a token without a role', () => {
    logIn(undefined);

    expect(navigateByUrl).toHaveBeenCalledWith('/');
  });

  it('shows an error and stays on the page for wrong credentials', () => {
    component['email'] = 'user@example.com';
    component['password'] = 'wrong';
    component.submit();
    httpMock.expectOne(loginUrl).flush({ error: 'bad' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(component['error']()).toBe('Identifiants invalides.');
    expect(navigateByUrl).not.toHaveBeenCalled();
  });
});
