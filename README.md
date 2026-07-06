# SportDay - Sports Event Management System

A full-stack sports event management application built with Spring Boot 4 (Java 21) and Next.js 15.

## Features

### 1. Event System
- Create, update, enable/disable sporting events
- Event types: Track (100M, 200M, 400M, 800M, 1500M, 5000M), Field (Shot Put, Discus, Javelin, Hammer, Long Jump, High Jump, Triple Jump, Pole Vault), Relays, Hurdles
- Set max participants, event date, and location

### 2. Enrollment System
- User registration and JWT-based authentication
- Browse and enroll in events
- View personal enrollment history
- Automatic capacity management

### 3. User Management
- Admin dashboard to manage all users
- Role-based access control (USER, MANAGER, ADMIN)
- Enable/disable user accounts

### 4. Event Result System
- Record marks for enrolled athletes (e.g., 100M: 0:14.123)
- Automatic leaderboard with ranking
- Support for time and distance marks

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 4.0, Java 21 |
| Database | MySQL 8.0 (Docker) |
| ORM | Spring Data JPA (Hibernate) |
| Security | Spring Security + JWT |
| Frontend | Next.js 15, React 19, TypeScript |
| Styling | Custom CSS |

## Project Structure

```
sportday/
├── backend/               # Spring Boot 4 backend
│   ├── src/main/java/com/sportday/
│   │   ├── controller/    # REST API controllers
│   │   ├── service/       # Business logic services
│   │   ├── entity/        # JPA entities
│   │   ├── repository/    # Spring Data repositories
│   │   ├── dto/           # Data transfer objects
│   │   ├── security/      # JWT auth components
│   │   ├── config/        # Configuration classes
│   │   └── exception/     # Exception handling
│   └── pom.xml
├── frontend/              # Next.js 15 frontend
│   ├── app/               # App router pages
│   ├── components/        # React components
│   └── lib/               # API client & auth context
└── docker-compose.yml     # MySQL Docker setup
```

## Prerequisites

- Java 21+
- Maven 3.8+
- Node.js 20+
- Docker & Docker Compose

## Quick Start

### 1. Start MySQL Database

```bash
docker-compose up -d
```

### 2. Start Backend

```bash
cd backend
mvn spring-boot:run
```

Backend runs at `http://localhost:8080`

Default admin credentials:
- Username: `admin`
- Password: `admin123`

### 3. Start Frontend

```bash
cd frontend
npm install
npm run dev
```

Frontend runs at `http://localhost:3000`

## API Endpoints

### Authentication
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/login` | Login |
| POST | `/api/auth/register` | Register new user |

### Events
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/events` | Public | List all events |
| GET | `/api/events/{id}` | Public | Get event details |
| POST | `/api/events` | Admin/Manager | Create event |
| PUT | `/api/events/{id}` | Admin/Manager | Update event |
| PATCH | `/api/events/{id}/enable` | Admin/Manager | Enable/disable event |
| DELETE | `/api/events/{id}` | Admin | Delete event |

### Enrollments
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/enrollments/{eventId}` | Authenticated | Enroll in event |
| DELETE | `/api/enrollments/{eventId}` | Authenticated | Cancel enrollment |
| GET | `/api/enrollments/my` | Authenticated | My enrollments |
| GET | `/api/enrollments/check/{eventId}` | Authenticated | Check enrollment |

### Results
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/results/event/{eventId}` | Public | Get event results |
| GET | `/api/results/user/{userId}` | Public | Get user results |
| POST | `/api/results` | Admin/Manager | Record result |
| DELETE | `/api/results/{id}` | Admin/Manager | Delete result |

### Users
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| GET | `/api/users/me` | Authenticated | Current user profile |
| PUT | `/api/users/me` | Authenticated | Update profile |
| GET | `/api/users` | Admin/Manager | List all users |
| PATCH | `/api/users/{id}/enable` | Admin | Enable/disable user |
| DELETE | `/api/users/{id}` | Admin | Delete user |

### Admin
| Method | Endpoint | Access | Description |
|--------|----------|--------|-------------|
| POST | `/api/admin/managers` | Admin | Create manager account |

## Roles

- **USER** - Can browse events, enroll, view results
- **MANAGER** - Can create/manage events, record results
- **ADMIN** - Full access including user management

## Example Usage

### Create an Event (Admin/Manager)
```json
POST /api/events
{
  "name": "100M Sprint Final",
  "description": "Men's 100M Sprint Championship",
  "type": "RUN_100M",
  "eventDate": "2026-07-15",
  "location": "Main Stadium",
  "maxParticipants": 8
}
```

### Record a Result (Admin/Manager)
```
POST /api/results?userId=2&eventId=1&mark=10.123&unit=seconds&notes=Wind +1.2 m/s
```

### Enroll in Event (User)
```
POST /api/enrollments/1
Authorization: Bearer <jwt_token>
```
