//
//  Learner.swift
//  namaz
//
//  Created by Nursultan Askarbekuly on 06.07.2023.
//  Copyright © 2023 Nursultan Askarbekuly. All rights reserved.
//

import Foundation

public class Learner: Codable {
    public var id: UUID
    private var properties: [String: String]
    
    /// Optional dictionary for property-level server override flags
    public var serverOverrides: [String: Bool]?

    enum CodingKeys: String, CodingKey {
        case id
        case properties
        case serverOverrides
    }

    init(id: UUID, properties: [String: String] = [:], serverOverrides: [String: Bool]? = nil) {
        self.id = id
        self.properties = properties
        self.serverOverrides = serverOverrides
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        
        // Filter out empty keys/values
        let cleanProperties = properties.filter { !$0.key.isEmpty && !$0.value.isEmpty }
        try container.encode(cleanProperties, forKey: .properties)
        
        try container.encodeIfPresent(serverOverrides, forKey: .serverOverrides)
    }

    required public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try container.decode(UUID.self, forKey: .id)
        
        if let decodedProperties = try? container.decode([String: String].self, forKey: .properties) {
            self.properties = decodedProperties.filter { !$0.key.isEmpty && !$0.value.isEmpty }
        } else {
            self.properties = [:]
        }

        // If serverOverrides isn't provided by older versions, it just remains nil
        self.serverOverrides = try? container.decodeIfPresent([String: Bool].self, forKey: .serverOverrides)
    }
    
    // Access properties
    public func getProperty(_ key: String) -> String? {
        return self.properties[key]
    }
    
    public func getAllProperties() -> [String: String] {
        return self.properties
    }
    
    // Local setter
    fileprivate func setProperty(_ value: String, forKey key: String) {
        guard !key.isEmpty, !value.isEmpty else { return }
        self.properties[key] = value
    }
}

extension Learner: Equatable {
    public static func == (lhs: Learner, rhs: Learner) -> Bool {
        return lhs.id == rhs.id && NSDictionary(dictionary: lhs.properties).isEqual(to: rhs.properties)
    }
}


//
//  BackendAPI.swift
//  namaz
//
//  Created by Nursultan Askarbekuly on 06.07.2023.
//  Copyright © 2023 Nursultan Askarbekuly. All rights reserved.
//

import Foundation

public class LearnerService {

    public static var shared = LearnerService()
    
    public var localLearner: Learner?
    
    public var learnerId: UUID? {
        localLearner?.id
    }

    private var remoteLearner: Learner?

    private let userDefaultsKey = "current_learner"
    private var lastLearnerUpdateAttempt: Date?
    private lazy var learnerUrl = "\(StorageDelegate.learnerStorage.serverUrl)/learner/\(StorageDelegate.learnerStorage.learnerCollectionName)"
    
    public func kickstartLocalLearner(predefinedAnalyticsId: UUID? = nil) {
        
        if let localLearner = retrieveLocalLearner() {
            self.localLearner = localLearner
            if let predefinedAnalyticsId {
                self.localLearner?.id = predefinedAnalyticsId
            }
            
            #if DEBUG
            print("Retrieved local learner with id: \(localLearner.id), lowercase: \(localLearner.id.uuidString.lowercased())")
            #endif
            
        } else if let appBuildVersion = Bundle.main.infoDictionary?["CFBundleVersion"] as? String {
            self.localLearner = Learner(id: predefinedAnalyticsId ?? UUID(), properties: ["bundleVersionAtInstall": appBuildVersion])
            #if DEBUG
            print("Created a new local learner with id: \(localLearner?.id.uuidString ?? "NO ID"), lowercase: \(localLearner?.id.uuidString.lowercased() ?? "NO ID")")
            #endif
        } else {
            print(#function, "error creating a learner")
        }
        saveLocalLearner()
    }
    
    public func setLearnerProperty(_ value: String, forKey propertyKey: String) {
        localLearner?.setProperty("\(value)", forKey: propertyKey)
        saveLocalLearner()
    }
       
    /// save local => update remote
    private func saveLocalLearner() {
        
        guard let learner = self.localLearner else {
            // The current learner is nil
            return
        }
        
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = .prettyPrinted
            let data = try encoder.encode(learner)
            StorageDelegate.learnerStorage.store(data, forKey: userDefaultsKey)
        } catch {
            print("Error encoding learner: \(error)")
            return
        }
        
        guard #available(iOS 15.0, *) else {
            return
        }
        
        if let lastTime = lastLearnerUpdateAttempt,
           Date().timeIntervalSince(lastTime) < 60 {
            return
        }
        
        if LearnerService.shared.remoteLearner != nil {
            lastLearnerUpdateAttempt = Date()
            Task {
                do {
                    LearnerService.shared.remoteLearner = try await LearnerService.shared.updateRemoteLearner()
                } catch {
                    print(#function, "error updating remote learner: \(error.localizedDescription)")
                }
            }
        }
    }
    
    @available(iOS 13.0, *)
    public func kickstartRemoteLearner() {
        guard let localLearner = self.localLearner else {
            print(#function, "error: no local learner")
            return
        }

        Task {
            do {
                let fetchedRemote = try await self.getRemoteLearner(localLearner.id.uuidString.lowercased())
                #if DEBUG
                print("Fetched remote learner:", String(describing: fetchedRemote))
                #endif
                
                // Merge remote -> local, respecting override flags
                let merged = mergeLearners(local: localLearner, remote: fetchedRemote)
                self.localLearner = merged
                saveLocalLearner()  // persist the merged result
                NotificationCenter.default.post(Notification(name: Notification.Name("reload_app_data")))

                // If desired, update the server with the merged version
                // (so the server also picks up local changes on non-overridden properties)
                if merged != fetchedRemote {
                    #if DEBUG
                    print("Updating remote learner...")
                    #endif
                    let updatedRemote = try await updateRemoteLearner()
                    self.remoteLearner = updatedRemote
                } else {
                    self.remoteLearner = fetchedRemote
                }
            } catch {
                // If remote doesn't exist or some error, maybe create new remote or handle error
                do {
                    self.remoteLearner = try await self.createRemoteLearner()
                } catch {
                    print(#function, "error creating remote learner:", error.localizedDescription)
                }
            }
        }
    }
        
    @available(iOS 13.0.0, *)
    public func createRemoteLearner() async throws -> Learner {
        
        guard let learner = self.localLearner else {
            print(#function, "Error learner update failed: local learner is nil")
            throw ServiceError.missingInput
        }
        
//        print(#function, "attempting to create a learner:", learner)
        
        guard let url = URL(string: learnerUrl) else {
            throw ServiceError.failedURLInitialization
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            let requestBody = try JSONEncoder().encode(learner)
            request.httpBody = requestBody
        } catch {
            print("Failed to encode learner: \(error)")
        }
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200...299).contains(httpResponse.statusCode) else {
                throw ServiceError.requestFailed
            }
            guard let learner = try? JSONDecoder().decode(Learner.self, from: data) else {
                throw ServiceError.decodingFailed
            }
            return learner
        } catch {
            // Handle other errors
            print(#function, "error creating remote learner: \(error.localizedDescription)")
            throw error
        }
    }
    
    private func retrieveLocalLearner() -> Learner? {
        guard let learnerData = StorageDelegate.learnerStorage.retrieve(forKey: userDefaultsKey) as? Data else {
            return nil
        }

        do {
            return try JSONDecoder().decode(Learner.self, from: learnerData)
        } catch {
            print("Error decoding learner: \(error)")
            return nil
        }
    }
    
    @available(iOS 13.0.0, *)
    public func getRemoteLearner(_ id: String) async throws -> Learner {
        /// FIXME can it get the learner from firestore? ≥,fdscaxz
        guard let url = URL(string: "\(learnerUrl)/\(id)") else {
            print(#function, "Failed URL initialization")
            throw ServiceError.failedURLInitialization
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            
            guard let httpResponse = response as? HTTPURLResponse else {
                print(#function, "failed response initialization")
                throw ServiceError.failedResponseInitialization
            }
            
            guard (200...299).contains(httpResponse.statusCode) else {
                print(#function, "Request failed with \(httpResponse.statusCode)")
                throw ServiceError.requestFailed
            }
            
            //                print(String(data: data, encoding: .utf8))
            
            guard let remoteLearner = try? JSONDecoder().decode(Learner.self, from: data) else {
                print(#function, "Failed to initilize learner from data:", String(decoding: data, as: UTF8.self))
                throw ServiceError.decodingFailed
            }
            
//            print("remote learner retrieved:", remoteLearner)
            
            return remoteLearner
            
        } catch {
            // Handle other errors
            print(#function, "error getting the remote learner: \(error.localizedDescription)")
            throw error
        }
    }
    
    /// Merge remote learner into local, respecting serverOverrides
    func mergeLearners(local: Learner, remote: Learner) -> Learner {
        // Start with a copy of local
        let mergedLearner = Learner(
            id: local.id,
            properties: local.getAllProperties(),
            serverOverrides: remote.serverOverrides // or keep local's if that makes sense
        )
        
        // For each remote property
        for (key, remoteValue) in remote.getAllProperties() {
            
            let shouldOverride = remote.serverOverrides?[key] == true
            
            if shouldOverride {
                // Server override => forcibly use remote property
                mergedLearner.setProperty(remoteValue, forKey: key)
            } else {
                // If local doesn't have the property or it's empty, adopt the remote property
                // (Alternatively, we could skip adopting remote if we strictly want "local wins" on non-overridden keys)
                if mergedLearner.getProperty(key) == nil {
                    mergedLearner.setProperty(remoteValue, forKey: key)
                }
                // If local has a value, we do nothing—local remains
            }
        }
        
        // If remote has no override and local has a property, local property is kept.
        return mergedLearner
    }

    @available(iOS 13.0.0, *)
    public func updateRemoteLearner() async throws -> Learner {
                
        guard let learner = self.localLearner else {
            print(#function, "Error learner update failed: local learner is nil")
            throw ServiceError.missingInput
        }
        
        guard let url = URL(string: "\(learnerUrl)") else {
            throw ServiceError.failedURLInitialization
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.addValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = .prettyPrinted
            let requestBody = try encoder.encode(learner)
            request.httpBody = requestBody
        } catch {
            print("Failed to encode learner: \(error)")
            throw ServiceError.encodingFailed
        }
        
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                print(#function, "failed response initialization")
                throw ServiceError.failedResponseInitialization
            }
            
            guard (200...299).contains(httpResponse.statusCode) else {
                print(#function, "Request failed with \(httpResponse.statusCode)")
                throw ServiceError.requestFailed
            }
            
            guard let updatedLearner = try? JSONDecoder().decode(Learner.self, from: data) else {
                throw ServiceError.decodingFailed
            }
            
//            print(#function, "learner updated successfully: \(updatedLearner)")
            
            return updatedLearner
        } catch {
            // Handle other errors
            print("failed to update remote learner")
            throw error
        }
    }
    
    
}
