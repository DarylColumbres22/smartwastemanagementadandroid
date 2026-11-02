 Smart Waste Management System with Secured Data Transmission

An Android-based Smart Waste Management System that integrates Internet of Things (IoT), Firebase, and Google's Gemini AI to support smarter waste monitoring and management.

Overview

The Smart Waste Management System is an academic capstone project developed to improve waste monitoring through mobile technology and IoT integration.

The application was developed using Kotlin for Android, with Firebase serving as its backend platform. It also integrates Google's Gemini AI, a Large Language Model (LLM), to provide AI-powered functionality within the system.

Key Features

- **Android Mobile Application** – Mobile interface for accessing system functionality.
- **IoT Integration** – Connects the waste monitoring hardware with the application.
- **Firebase Integration** – Supports backend data management and communication.
- **Google Gemini AI** – Integrates LLM capabilities into the application.
- **Secured Data Transmission** – Focuses on secure communication between system components.

 Technologies Used

| Technology | Purpose |
|---|---|
| Kotlin | Android application development |
| Android Studio | Development environment |
| Firebase | Backend and cloud services |
| Google Gemini API | AI / LLM integration |
| IoT | Hardware-based waste monitoring |

 System Architecture

The system consists of three main components:

1. **IoT Hardware** – Collects waste monitoring data.
2. **Firebase Backend** – Handles data storage and communication.
3. **Android Application** – Provides the user interface and Gemini AI functionality.

 Getting Started

Follow these instructions to set up and run the project locally.

### Prerequisites

Before running the application, make sure you have the following installed:

- Android Studio
- Android SDK
- Kotlin support
- Git
- A Firebase project
- Google Gemini API key

### Installation

**1. Clone the repository**

```bash
git clone https://github.com/YOUR-USERNAME/YOUR-REPOSITORY.git
```

**2. Open the project**

- Launch Android Studio.
- Select `Open`.
- Navigate to the cloned project folder.
- Wait for Gradle synchronization to finish.

**3. Configure Firebase**

- Create or open a project in the Firebase Console.
- Register the Android application using the correct package name.
- Download the `google-services.json` configuration file.
- Place it inside the app module directory:

```text
Smart-Waste-Management/
└── app/
    └── google-services.json
```

- Enable the Firebase services required by the application.
- Make sure the Firebase configuration matches the project's existing implementation.

**4. Configure Google Gemini API**

- Obtain an API key through Google AI Studio.
- Configure the API key according to the project's existing implementation.
- Do not commit API keys or other private credentials to GitHub.

**5. Build and run**

- Connect an Android device with USB debugging enabled or start an Android Emulator.
- Select the application configuration in Android Studio.
- Click the **Run** button.
- Wait for the application to build and install.

## Project Structure

```text
Smart-Waste-Management/
│
├── app/
│   ├── src/
│   └── build.gradle.kts
│
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── README.md
└── .gitignore
```

*This is an illustrative structure. Adjust it to match the actual repository.*

## My Contributions

As a member of the capstone development team, my involvement included:

- Android application development using Kotlin.
- Firebase integration for backend communication and data management.
- Integration of Google's Gemini AI.
- Contributions to IoT and mobile application integration.

## Screenshots and Demonstration

Screenshots of the Android application, AI integration, and IoT hardware will be added here.

## Project Information

- **Project Type:** Academic Capstone Project
- **Platform:** Android
- **Language:** Kotlin
- **Backend:** Firebase
- **AI Integration:** Google Gemini
- **Category:** Mobile Development / IoT / AI Integration
- **Status:** Academic Project

## Security Notice

- Never upload API keys, passwords, or private Firebase credentials.
- Keep sensitive configuration files out of public repositories when appropriate.
- Use a `.gitignore` file to prevent accidental exposure of sensitive files.

## Disclaimer

This project was developed as part of an academic capstone requirement and is presented for educational and portfolio purposes.
