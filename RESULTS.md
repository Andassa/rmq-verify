# Résultats

> Généré par `make bench` le 2026-10-24T13:03:44.777787Z à partir du commit `d27bbe6`. Ne pas modifier à la main : relancer `make bench`.

- Hôte : Darwin arm64, 8 CPUs, Docker 29.5.2
- Exécutions : 24 (historiques bruts dans `results/`)
- Image RABBITMQ_3_13: `rabbitmq:3.13.7-management@sha256:e582c0bc7766f3342496d8485efb5a1df782b5ce3886ad017e2eaae442311f69`
- Image RABBITMQ_4_4: `rabbitmq:4.4.0-management@sha256:c200fd496482cda18c412be4e6f244830e1e75949b8cf5f0e887cea9ea456109`
- Image TOXIPROXY: `ghcr.io/shopify/toxiproxy:2.13.0@sha256:5d3dae09bc185dcc5ff6cf43abf6062a4a720aff0c4306a471e773acdcd107aa`

## Matrice

Chaque cellule : exécutions avec une violation / répétitions, puis les totaux.

| Scénario | RabbitMQ 3.13.7 | RabbitMQ 4.4.0 |
|---|---|---|
| `classic-kill` | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 3.3 s | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.0 s |
| `classic-maxlength-control` | **2/2** invalides · perdus 6235 · absents après reprise 0 · dup 0 · ordre 6235 · reprise max 0.0 s | **2/2** invalides · perdus 8049 · absents après reprise 0 · dup 0 · ordre 8049 · reprise max 0.0 s |
| `quorum-kill-pause` | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.1 s | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.5 s |
| `quorum-network` | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.1 s | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.4 s |
| `quorum-partitions` | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.0 s | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 0.0 s |
| `stream-kill-partition` | **0/2** invalides · perdus 0 · absents après reprise 0 · dup 0 · ordre 0 · reprise max 4.7 s | **1/2** invalides · perdus 0 · absents après reprise 0 · dup 1 · ordre 0 · reprise max 1.4 s |

## Détail

| Scénario | Version | File | Exécutions | Invalides | Inconnues | Confirmés | Indéterminés | Perdus | Absents après reprise | Doublons inattendus | Violations d'ordre | Inattendus | Reprise max (s) | Succès min |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `classic-kill` | 3.13.7 | CLASSIC | 2 | 0 | 0 | 6652 | 12 | 0 | 0 | 0 | 0 | 0 | 3.3 | 99.7% |
| `classic-kill` | 4.4.0 | CLASSIC | 2 | 0 | 0 | 9932 | 8 | 0 | 0 | 0 | 0 | 0 | 0.0 | 99.8% |
| `classic-maxlength-control` | 3.13.7 | CLASSIC | 2 | 2 | 0 | 6435 | 4 | 6235 | 0 | 0 | 6235 | 0 | 0.0 | 99.8% |
| `classic-maxlength-control` | 4.4.0 | CLASSIC | 2 | 2 | 0 | 8249 | 0 | 8049 | 0 | 0 | 8049 | 0 | 0.0 | 100.0% |
| `quorum-kill-pause` | 3.13.7 | QUORUM | 2 | 0 | 0 | 7358 | 17 | 0 | 0 | 0 | 0 | 0 | 0.1 | 99.7% |
| `quorum-kill-pause` | 4.4.0 | QUORUM | 2 | 0 | 0 | 8363 | 16 | 0 | 0 | 0 | 0 | 0 | 0.5 | 99.8% |
| `quorum-network` | 3.13.7 | QUORUM | 2 | 0 | 0 | 5589 | 0 | 0 | 0 | 0 | 0 | 0 | 0.1 | 100.0% |
| `quorum-network` | 4.4.0 | QUORUM | 2 | 0 | 0 | 4961 | 0 | 0 | 0 | 0 | 0 | 0 | 0.4 | 100.0% |
| `quorum-partitions` | 3.13.7 | QUORUM | 2 | 0 | 0 | 6815 | 12 | 0 | 0 | 0 | 0 | 0 | 0.0 | 99.6% |
| `quorum-partitions` | 4.4.0 | QUORUM | 2 | 0 | 0 | 7457 | 6 | 0 | 0 | 0 | 0 | 0 | 0.0 | 99.9% |
| `stream-kill-partition` | 3.13.7 | STREAM | 2 | 0 | 0 | 8889 | 13 | 0 | 0 | 0 | 0 | 0 | 4.7 | 99.8% |
| `stream-kill-partition` | 4.4.0 | STREAM | 2 | 1 | 0 | 4699 | 12 | 0 | 0 | 1 | 0 | 0 | 1.4 | 99.3% |

## Contre-exemples

- `classic-maxlength-control` sur 3.13.7, exécution `r-20261024-112514-2c89`, FifoOrdering : 4268 violation(s), p. ex. confirmed publisher-2#1034 in rmqv.r-20261024-112514-2c89.classic.0 missing while #1035 was delivered (opérations [8697, 8701, 9003])
- `classic-maxlength-control` sur 3.13.7, exécution `r-20261024-112654-2c8a`, FifoOrdering : 1967 violation(s), p. ex. confirmed publisher-0#489 in rmqv.r-20261024-112654-2c8a.classic.0 missing while #490 was delivered (opérations [4047, 4050, 4350])
- `classic-maxlength-control` sur 4.4.0, exécution `r-20261024-115237-2c89`, FifoOrdering : 3972 violation(s), p. ex. confirmed publisher-3#959 in rmqv.r-20261024-115237-2c89.classic.0 missing while #960 was delivered (opérations [8083, 8085, 8385])
- `classic-maxlength-control` sur 4.4.0, exécution `r-20261024-115416-2c8a`, FifoOrdering : 4077 violation(s), p. ex. confirmed publisher-3#1001 in rmqv.r-20261024-115416-2c8a.classic.0 missing while #1002 was delivered (opérations [8307, 8309, 8592])
- `stream-kill-partition` sur 4.4.0, exécution `r-20261024-121201-4011`, DuplicateDeliveries : 1 violation(s), p. ex. publisher-2#534 in rmqv.r-20261024-121201-4011.stream.0 read 4 times at 2 distinct offsets (opérations [6169, 6173, 13005, 13009])

## Comment lire ces résultats

- Une violation est une preuve : ses opérations sont dans l'historique archivé et `rmq-verify check` la retrouve.
- Une cellule propre ne prouve pas la correction : elle couvre ces plans, ces durées et ces répétitions.
- La graine reproduit le plan de fautes et la charge, pas le timing du broker ; les répétitions mesurent la fréquence d'une anomalie.
- Le scénario `classic-maxlength-control` jette des messages confirmés exprès (`x-max-length` et `drop-head`) : il montre que les checkers voient les pertes. Ce n'est pas un défaut de RabbitMQ.
- Trois nœuds sur une machine (Docker dans une VM). Le client joint chaque nœud via Toxiproxy, donc il n'est pas coincé d'un seul côté d'une partition.
- Un publish sans confirm est indéterminé, jamais compté comme perdu. Voir docs/METHODOLOGY.md.
