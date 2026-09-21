import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { User, UserService } from './user.service';

describe('UserService', () => {
  let service: UserService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.identityApiUrl}/users`;

  const sampleUser: User = {
    id: 'user-1',
    email: 'admin@example.com',
    role: 'ADMIN',
    createdAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [UserService],
    });

    service = TestBed.inject(UserService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('list() performs a GET /users and returns the response', () => {
    let result: User[] | undefined;

    service.list().subscribe((users) => (result = users));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('GET');
    req.flush([sampleUser]);

    expect(result).toEqual([sampleUser]);
  });

  it('create() performs a POST /users with the given email and password', () => {
    let result: User | undefined;

    service.create('admin@example.com', 'secret').subscribe((user) => (result = user));

    const req = httpMock.expectOne(baseUrl);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'admin@example.com', password: 'secret' });
    req.flush(sampleUser);

    expect(result).toEqual(sampleUser);
  });

  it('update() performs a PATCH /users/{id} with the given email', () => {
    let result: User | undefined;

    service.update('user-1', 'new@example.com').subscribe((user) => (result = user));

    const req = httpMock.expectOne(`${baseUrl}/user-1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual({ email: 'new@example.com' });
    req.flush(sampleUser);

    expect(result).toEqual(sampleUser);
  });

  it('delete() performs a DELETE /users/{id}', () => {
    let completed = false;

    service.delete('user-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(`${baseUrl}/user-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });
});
