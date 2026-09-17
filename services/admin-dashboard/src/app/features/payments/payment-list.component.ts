import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { AlertComponent } from '../../shared/ui/alert/alert.component';
import { ButtonComponent } from '../../shared/ui/button/button.component';
import { CardComponent } from '../../shared/ui/card/card.component';
import { InputComponent } from '../../shared/ui/input/input.component';
import { Payment, PaymentService } from './payment.service';

/**
 * Payments screen: list (GET /payments) plus create, status transition
 * (PENDING -> COMPLETED/FAILED only) and delete actions. Every successful
 * mutation reloads the list from the server instead of mutating the local
 * signal directly.
 */
@Component({
  selector: 'app-payment-list',
  imports: [FormsModule, AlertComponent, ButtonComponent, CardComponent, InputComponent],
  templateUrl: './payment-list.component.html',
})
export class PaymentListComponent implements OnInit {
  private readonly paymentService = inject(PaymentService);

  protected readonly payments = signal<Payment[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  // Create form state.
  protected createAmount: number | null = null;
  protected createCurrency = '';
  protected readonly creating = signal(false);
  protected readonly createError = signal<string | null>(null);

  // Status update state.
  protected readonly updatingPaymentId = signal<string | null>(null);
  protected readonly updateError = signal<string | null>(null);

  // Delete state.
  protected readonly deletingPaymentId = signal<string | null>(null);
  protected readonly deleteError = signal<string | null>(null);

  ngOnInit(): void {
    this.loadPayments();
  }

  private loadPayments(): void {
    this.loading.set(true);
    this.error.set(null);
    this.paymentService.list().subscribe({
      next: (payments) => {
        this.payments.set(payments);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Impossible de charger la liste des paiements.');
        this.loading.set(false);
      },
    });
  }

  createPayment(): void {
    if (this.createAmount === null) {
      return;
    }

    this.creating.set(true);
    this.createError.set(null);

    this.paymentService.create(this.createAmount, this.createCurrency).subscribe({
      next: () => {
        this.creating.set(false);
        this.createAmount = null;
        this.createCurrency = '';
        this.loadPayments();
      },
      error: (err: HttpErrorResponse) => {
        this.creating.set(false);
        this.createError.set(this.extractErrorMessage(err, 'Impossible de créer ce paiement.'));
      },
    });
  }

  markCompleted(payment: Payment): void {
    this.updateStatus(payment, 'COMPLETED');
  }

  markFailed(payment: Payment): void {
    this.updateStatus(payment, 'FAILED');
  }

  private updateStatus(payment: Payment, status: 'COMPLETED' | 'FAILED'): void {
    this.updatingPaymentId.set(payment.id);
    this.updateError.set(null);

    this.paymentService.updateStatus(payment.id, status).subscribe({
      next: () => {
        this.updatingPaymentId.set(null);
        this.loadPayments();
      },
      error: (err: HttpErrorResponse) => {
        this.updatingPaymentId.set(null);
        this.updateError.set(this.extractErrorMessage(err, 'Impossible de mettre à jour le statut de ce paiement.'));
      },
    });
  }

  deletePayment(payment: Payment): void {
    if (!confirm(`Supprimer le paiement ${payment.id} ?`)) {
      return;
    }

    this.deletingPaymentId.set(payment.id);
    this.deleteError.set(null);

    this.paymentService.delete(payment.id).subscribe({
      next: () => {
        this.deletingPaymentId.set(null);
        this.loadPayments();
      },
      error: (err: HttpErrorResponse) => {
        this.deletingPaymentId.set(null);
        this.deleteError.set(this.extractErrorMessage(err, 'Impossible de supprimer ce paiement.'));
      },
    });
  }

  private extractErrorMessage(err: HttpErrorResponse, fallback: string): string {
    return typeof err.error?.error === 'string' ? err.error.error : fallback;
  }
}