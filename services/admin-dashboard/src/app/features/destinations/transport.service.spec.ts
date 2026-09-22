import { HttpClientTestingModule, HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { environment } from '../../../environments/environment';
import { Transport, TransportService, TransportUpdateInput } from './transport.service';

describe('TransportService', () => {
  let service: TransportService;
  let httpMock: HttpTestingController;

  const baseUrl = `${environment.travelApiUrl}/destinations`;

  const transport: Transport = {
    id: 'transport-1',
    mode: 'TRAIN',
    durationMinutes: 180,
    destinationId: 'dest-2',
    destinationName: 'Porto',
    destinationCountry: 'Portugal',
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [TransportService],
    });

    service = TestBed.inject(TransportService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('listOutgoing() performs a GET /destinations/{id}/transports and returns the response', () => {
    let result: Transport[] | undefined;

    service.listOutgoing('dest-1').subscribe((transports) => (result = transports));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/transports`);
    expect(req.request.method).toBe('GET');
    req.flush([transport]);

    expect(result).toEqual([transport]);
  });

  it('create() performs a POST /destinations/{fromId}/transports with the given body', () => {
    let result: Transport | undefined;

    service.create('dest-1', 'dest-2', 'TRAIN', 180).subscribe((created) => (result = created));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/transports`);
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ toDestinationId: 'dest-2', mode: 'TRAIN', durationMinutes: 180 });
    req.flush(transport);

    expect(result).toEqual(transport);
  });

  it('update() performs a PATCH /destinations/{fromId}/transports/{transportId} with the given body', () => {
    let result: Transport | undefined;
    const input: TransportUpdateInput = { mode: 'PLANE', durationMinutes: 90 };

    service.update('dest-1', 'transport-1', input).subscribe((updated) => (result = updated));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/transports/transport-1`);
    expect(req.request.method).toBe('PATCH');
    expect(req.request.body).toEqual(input);
    req.flush({ ...transport, mode: 'PLANE', durationMinutes: 90 });

    expect(result).toEqual({ ...transport, mode: 'PLANE', durationMinutes: 90 });
  });

  it('delete() performs a DELETE /destinations/{fromId}/transports/{transportId}', () => {
    let completed = false;

    service.delete('dest-1', 'transport-1').subscribe(() => (completed = true));

    const req = httpMock.expectOne(`${baseUrl}/dest-1/transports/transport-1`);
    expect(req.request.method).toBe('DELETE');
    req.flush(null);

    expect(completed).toBe(true);
  });
});
