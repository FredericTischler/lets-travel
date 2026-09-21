#!/usr/bin/env python3
"""Alimente la stack locale (Docker Compose, via Traefik) avec un jeu de données de démonstration.

Idempotent : peut être relancé, les comptes/voyages/avis déjà présents sont réutilisés.
Prérequis : la stack tourne (identity/payment/travel derrière https://*.localhost), un compte ADMIN
existe (SEED_ADMIN_EMAIL / SEED_ADMIN_PASSWORD, par défaut a@a.com / password1), et l'utilisateur
courant peut lancer `docker exec` (les participations passées et les dates de paiement ne sont pas
créables par l'API : elles sont posées directement dans Neo4j / PostgreSQL, comme le font les tests).

Tous les comptes de démo ont le mot de passe DEMO_PASSWORD (password1).
"""
import json
import os
import ssl
import subprocess
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, datetime, timedelta, timezone

ID, TR, PAY = "https://identity.localhost", "https://travel.localhost", "https://payment.localhost"
ADMIN_EMAIL = os.environ.get("SEED_ADMIN_EMAIL", "a@a.com")
ADMIN_PASSWORD = os.environ.get("SEED_ADMIN_PASSWORD", "password1")
DEMO_PASSWORD = "password1"
DOMAIN = "lets-travel.test"
CTX = ssl._create_unverified_context()  # certificat auto-signé de Traefik en local
TODAY = date.today()


def call(method, url, body=None, token=None):
    req = urllib.request.Request(url, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, context=CTX, timeout=30) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, raw


def env_file_value(key):
    with open("/opt/travel-plan/.env", encoding="utf-8") as f:
        for line in f:
            if line.startswith(key + "="):
                return line.split("=", 1)[1].strip()
    sys.exit(f"{key} introuvable dans /opt/travel-plan/.env")


def cypher(statement):
    r = subprocess.run(
        ["docker", "exec", "-i", "-e", "PW=" + env_file_value("NEO4J_PASSWORD"), "travel-plan-neo4j",
         "sh", "-c", 'cypher-shell -u neo4j -p "$PW"'],
        input=statement, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("cypher-shell: " + r.stderr[:300])


def sql_payment(statement):
    r = subprocess.run(["docker", "exec", "travel-plan-postgres", "psql", "-U", "postgres", "-d", "payment_db",
                        "-v", "ON_ERROR_STOP=1", "-c", statement], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit("psql: " + r.stderr[:300])


def account(name, role):
    email = f"{name}@{DOMAIN}"
    call("POST", ID + "/users", {"email": email, "password": DEMO_PASSWORD, "role": role})  # 409 si déjà là
    s, d = call("POST", ID + "/login", {"email": email, "password": DEMO_PASSWORD})
    if s != 200:
        sys.exit(f"login {email} -> {s} {d}")
    return {"id": d["id"], "token": d["token"], "email": email}


def d(offset):
    return (TODAY + timedelta(days=offset)).isoformat()


MANAGERS = ["marie.dupont", "jean.martin", "sofia.rossi"]
TRAVELERS = ["alice", "bob", "chloe", "david", "emma", "farid", "gabi", "hugo"]

# (nom, pays, manager, début (jours depuis aujourd'hui), durée, prix, capacité, activités, [(hébergement, type)])
TRAVELS = [
    ("Algarve Surf Camp", "Portugal", 0, -60, 7, 450, 12, ["surf", "yoga", "randonnée"], [("Casa do Mar", "hôtel")]),
    ("Kyoto Temples & Thé", "Japon", 0, -45, 10, 1800, 10, ["temples", "cérémonie du thé", "cuisine"], [("Ryokan Sakura", "ryokan")]),
    ("Alpes Suisses Ski", "Suisse", 1, -75, 6, 1200, 8, ["ski", "raclette", "spa"], [("Chalet Edelweiss", "chalet")]),
    ("Marrakech des Souks", "Maroc", 2, -30, 5, 520, 14, ["souks", "cuisine", "désert"], [("Riad Yasmine", "riad")]),
    ("Toscane et Vins", "Italie", 2, -100, 6, 780, 10, ["vin", "cuisine", "vélo"], [("Agriturismo Rosa", "gîte")]),
    ("Lisbonne Culture", "Portugal", 0, 20, 5, 390, 15, ["fado", "azulejos", "cuisine"], [("Hôtel Alfama", "hôtel")]),
    ("Tokyo Néon", "Japon", 0, 45, 9, 2100, 8, ["manga", "cuisine", "temples"], [("Capsule Shibuya", "hôtel")]),
    ("Zermatt Randonnée", "Suisse", 1, 35, 5, 950, 10, ["randonnée", "spa"], [("Chalet Matterhorn", "chalet")]),
    ("Rome Antique (visite guidée offerte)", "Italie", 2, 15, 4, 0, 20, ["histoire", "cuisine"], [("Pensione Colosseo", "hôtel")]),
    ("Sahara Étoilé", "Maroc", 2, 60, 6, 640, 12, ["désert", "chameau", "bivouac"], [("Bivouac Merzouga", "camp")]),
    ("Barcelone Tapas", "Espagne", 1, 25, 4, 0, 25, ["tapas", "architecture"], [("Hostal Gràcia", "auberge")]),
    ("Porto et Douro", "Portugal", 0, 8, 3, 180, 3, ["vin", "croisière"], [("Casa da Ribeira", "hôtel")]),
]

# (voyageur, voyage) participations passées -> note/commentaire (None = pas d'avis)
PAST = {
    ("alice", "Algarve Surf Camp"): (5, "Super semaine, moniteurs au top !"),
    ("alice", "Kyoto Temples & Thé"): (5, "Un rêve, organisation parfaite."),
    ("alice", "Alpes Suisses Ski"): (1, "Trop froid et mal organisé, je ne recommande pas."),
    ("bob", "Alpes Suisses Ski"): (5, "Neige incroyable et super chalet."),
    ("bob", "Toscane et Vins"): (4, "Très bons vins, un peu long en bus."),
    ("bob", "Algarve Surf Camp"): (3, "Correct, météo capricieuse."),
    ("chloe", "Kyoto Temples & Thé"): (4, "Très culturel, rythme soutenu."),
    ("chloe", "Marrakech des Souks"): (5, "Le riad était magnifique."),
    ("chloe", "Toscane et Vins"): (5, "Le meilleur séjour de l'année."),
    ("david", "Marrakech des Souks"): (2, "Logement décevant par rapport aux photos."),
    ("david", "Algarve Surf Camp"): (4, "Bonne ambiance."),
    ("emma", "Kyoto Temples & Thé"): (5, "Guide passionnant."),
    ("emma", "Alpes Suisses Ski"): (4, "Belles pistes, prix élevé."),
    ("emma", "Marrakech des Souks"): (4, "Dépaysement total."),
    ("farid", "Toscane et Vins"): (None, None),
    ("farid", "Marrakech des Souks"): (5, "Parfait, je repars !"),
    ("gabi", "Algarve Surf Camp"): (None, None),
    ("hugo", "Alpes Suisses Ski"): (2, "Trop de temps libre non encadré."),
    ("hugo", "Toscane et Vins"): (4, "Belle région, bons repas."),
}
# Inscriptions à venir déjà payées (posées directement) et annulées
UPCOMING_PAID = [("alice", "Lisbonne Culture"), ("alice", "Tokyo Néon"), ("chloe", "Tokyo Néon"),
                 ("bob", "Zermatt Randonnée"), ("emma", "Sahara Étoilé"), ("david", "Lisbonne Culture"),
                 ("hugo", "Porto et Douro"), ("farid", "Porto et Douro")]
CANCELLED = [("bob", "Sahara Étoilé"), ("gabi", "Tokyo Néon")]
# Inscriptions à venir gratuites, faites via l'API réelle
FREE_SUBS = [("alice", "Rome Antique (visite guidée offerte)"), ("bob", "Barcelone Tapas"),
             ("chloe", "Barcelone Tapas"), ("emma", "Rome Antique (visite guidée offerte)"),
             ("gabi", "Barcelone Tapas"), ("hugo", "Rome Antique (visite guidée offerte)")]
REPORTS = [("david", "jean.martin", "Organisation chaotique pendant le séjour au ski."),
           ("farid", "jean.martin", "Le chalet ne correspondait pas à la description."),
           ("chloe", "bob", "Comportement irrespectueux envers le guide.")]


def main():
    s, a = call("POST", ID + "/login", {"email": ADMIN_EMAIL, "password": ADMIN_PASSWORD})
    if s != 200:
        sys.exit(f"login admin {ADMIN_EMAIL} impossible ({s})")
    admin = a["token"]

    managers = [account(n, "TRAVEL_MANAGER") for n in MANAGERS]
    travelers = {n: account(n, "TRAVELER") for n in TRAVELERS}
    print(f"comptes : {len(managers)} managers, {len(travelers)} voyageurs (mot de passe {DEMO_PASSWORD})")

    s, existing = call("GET", TR + "/destinations", token=admin)
    by_name = {x["name"]: x for x in existing}
    dest = {}
    for name, country, m, start, days, price, cap, acts, accs in TRAVELS:
        if name in by_name:
            dest[name] = by_name[name]
            continue
        mgr = managers[m]
        body = {"name": name, "country": country, "startDate": d(start), "endDate": d(start + days),
                "managerId": mgr["id"], "price": price, "capacity": cap, "activities": acts,
                "accommodations": [{"name": n, "type": t, "checkIn": d(start), "checkOut": d(start + days)} for n, t in accs]}
        s, r = call("POST", TR + "/destinations", body, mgr["token"])
        if s != 201:
            sys.exit(f"création voyage {name} -> {s} {r}")
        dest[name] = r
    print(f"voyages : {len(dest)}")

    def seed_subscription(traveler, travel, status):
        sub_id = str(uuid.uuid4())
        cancelled = "datetime()" if status == "CANCELLED" else "null"
        cypher(f"""MATCH (d:Destination {{id: '{dest[travel]['id']}'}})
MERGE (t:TravelerRef {{userId: '{travelers[traveler]['id']}'}})
MERGE (t)-[s:SUBSCRIBED]->(d)
ON CREATE SET s.id = '{sub_id}', s.status = '{status}', s.subscribedAt = datetime(), s.cancelledAt = {cancelled};""")
        return sub_id

    payments = []  # (traveler, travel, sub_id, completed_at)
    for (tv, travel), _ in PAST.items():
        sub = seed_subscription(tv, travel, "ACTIVE")
        start = date.fromisoformat(dest[travel]["startDate"])
        payments.append((tv, travel, sub, datetime.combine(start - timedelta(days=12), datetime.min.time(), timezone.utc)))
    for tv, travel in UPCOMING_PAID:
        sub = seed_subscription(tv, travel, "ACTIVE")
        payments.append((tv, travel, sub, datetime.now(timezone.utc) - timedelta(days=3)))
    for tv, travel in CANCELLED:
        seed_subscription(tv, travel, "CANCELLED")
    print(f"participations : {len(PAST)} passées, {len(UPCOMING_PAID)} à venir payées, {len(CANCELLED)} annulées")

    ok = 0
    for (tv, travel), (rating, comment) in PAST.items():
        if rating is None:
            continue
        body = {"rating": rating, "comment": comment}
        s, r = call("POST", TR + f"/destinations/{dest[travel]['id']}/feedback", body, travelers[tv]["token"])
        ok += s in (201, 409)
        if s not in (201, 409):
            print("  avis refusé", tv, travel, s, r)
    print(f"avis : {ok}")

    for tv, travel in FREE_SUBS:
        call("POST", TR + f"/destinations/{dest[travel]['id']}/subscriptions", {}, travelers[tv]["token"])  # 409 si déjà là
    print(f"inscriptions gratuites : {len(FREE_SUBS)}")

    s, mine = call("GET", PAY + "/payments", token=admin)
    already = {(p.get("travelId"), p["userId"]) for p in mine if p.get("travelId")}
    created = 0
    for tv, travel, sub, when in payments:
        price = next(t[5] for t in TRAVELS if t[0] == travel)
        if price == 0 or (dest[travel]["id"], travelers[tv]["id"]) in already:
            continue
        body = {"userId": travelers[tv]["id"], "amount": price, "currency": "EUR",
                "travelId": dest[travel]["id"], "subscriptionRef": sub}
        s, p = call("POST", PAY + "/payments", body, travelers[tv]["token"])
        if s != 201:
            print("  paiement refusé", tv, travel, s, p)
            continue
        call("PATCH", PAY + f"/payments/{p['id']}/status", {"status": "COMPLETED"}, admin)
        sql_payment(f"update payments set completed_at = '{when.isoformat()}' where id = '{p['id']}';")
        created += 1
    print(f"paiements complétés : {created} (dates étalées sur plusieurs mois)")

    made = 0
    _, known = call("GET", ID + "/reports", token=admin)
    seen = {(r["reporterId"], r["reportedUserId"], r["reason"]) for r in known}
    for who, target, reason in REPORTS:
        reporter = travelers[who]
        tid = next((m["id"] for m in managers if m["email"].startswith(target)), None) or travelers[target]["id"]
        if (reporter["id"], tid, reason) in seen:
            continue
        s, r = call("POST", ID + "/reports", {"reportedUserId": tid, "reason": reason}, reporter["token"])
        made += s == 201
        if s == 201 and who == "david":
            call("PATCH", ID + f"/reports/{r['id']}/status", {"status": "REVIEWED"}, admin)
    print(f"signalements créés : {made}")
    print("terminé.")


if __name__ == "__main__":
    main()
