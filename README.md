# 🎓 EduPay-Leave-Backend

Welcome to the **EduPay-Leave-Backend** repository! 🚀 This is the backend system for the **School Management System**, responsible for handling student fee management, payments, leave tracking, and authentication with a secure and scalable architecture.

---

## 🛠️ Technologies Used

- **☕ Java 21** - High-performance backend language.
- **⚡ Spring Boot** - Framework for building RESTful APIs efficiently.
- **💳 Razorpay** - Seamless payment gateway integration.
- **🗄️ PostgreSQL** - Reliable relational database for storing data.

---

## 📋 Prerequisites

Before getting started, ensure you have the following installed:

✅ **Java 21** - [Download](https://adoptopenjdk.net/)  
✅ **Maven** - Build and dependency management tools  
✅ **PostgreSQL** - Database server for storing application data   
✅ **Razorpay Account** - Account with API keys for payments

---

## 📥 Installation

1️⃣ **Clone the repository:**
   ```bash
   git clone https://github.com/bhumitjoshi2003/EduPay-Leave-backend.git
   cd EduPay-Leave-backend
   ```

2️⃣ **Configure Database:**
   - Create a database (e.g., `edu_pay_leave_db`).
   - Update `application.properties` with database credentials:
     ```properties
     spring.datasource.url=jdbc:postgresql://localhost:5432/edu_pay_leave_db
     spring.datasource.username=root
     spring.datasource.password=password
     ```

4️⃣ **Configure Razorpay:**
   - Store API keys securely in environment variables or `application.properties`:
     ```properties
     razorpay.key=razorpay_key
     razorpay.secret=razorpay_secret
     ```

5️⃣ **Install dependencies:**
   ```bash
   mvn clean install  
   ```

---

## 🚀 Running the Application

1️⃣ **Start the Database Server** (PostgreSQl should be running).  
3️⃣ **Run the Backend Application:**
   ```bash
   mvn spring-boot:run  
   ```

---

## 🌍 Environment Variables

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | PostgreSQL database connection URL |
| `RAZORPAY_KEY` | Razorpay API key |
| `RAZORPAY_SECRET` | Razorpay secret key |

---



